/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.worker.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import tachyon.Constants;
import tachyon.worker.BlockHandler;
import tachyon.worker.BlocksLocker;
import tachyon.worker.tiered.StorageDir;

/**
 * This class has the main logic of the read path to process
 * {@link tachyon.worker.netty.BlockRequest} messages and return
 * {@link tachyon.worker.netty.BlockResponse} messages.
 */
@ChannelHandler.Sharable
public final class DataServerHandler extends ChannelInboundHandlerAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  private final BlocksLocker mLocker;

  public DataServerHandler(final BlocksLocker locker) {
    mLocker = locker;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
    // pipeline will make sure this is true
    final BlockRequest req = (BlockRequest) msg;

    final long blockId = req.getBlockId();
    final long offset = req.getOffset();
    final long len = req.getLength();
    final int lockId = mLocker.getLockId();
    final StorageDir storageDir = mLocker.lock(blockId, lockId);

    BlockHandler handler = null;
    try {
      validateInput(req);
      handler = storageDir.getBlockHandler(blockId);

      final long fileLength = handler.getLength();
      validateBounds(req, fileLength);
      final long readLength = returnLength(offset, len, fileLength);
      ChannelFuture future =
          ctx.writeAndFlush(new BlockResponse(blockId, offset, readLength, handler));
      future.addListener(ChannelFutureListener.CLOSE);
      future.addListener(new ClosableResourceChannelListener(handler));
      storageDir.accessBlock(blockId);
      LOG.info("Response remote request by reading from {}, preparation done.",
          storageDir.getBlockFilePath(blockId));
    } catch (Exception e) {
      // TODO This is a trick for now. The data may have been removed before remote retrieving.
      LOG.error("The file is not here : " + e.getMessage(), e);
      BlockResponse resp = BlockResponse.createErrorResponse(blockId);
      ChannelFuture future = ctx.writeAndFlush(resp);
      future.addListener(ChannelFutureListener.CLOSE);
      if (handler != null) {
        handler.close();
      }
    } finally {
      mLocker.unlock(blockId, lockId);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    LOG.warn("Exception thrown while processing request", cause);
    ctx.close();
  }

  /**
   * Returns how much of a file to read. When {@code len} is {@code -1}, then
   * {@code fileLength - offset} is used.
   */
  private long returnLength(final long offset, final long len, final long fileLength) {
    return (len == -1) ? fileLength - offset : len;
  }

  private void validateBounds(final BlockRequest req, final long fileLength) {
    Preconditions.checkArgument(req.getOffset() <= fileLength,
        "Offset(%s) is larger than file length(%s)", req.getOffset(), fileLength);
    Preconditions.checkArgument(req.getLength() == -1
        || req.getOffset() + req.getLength() <= fileLength,
        "Offset(%s) plus length(%s) is larger than file length(%s)", req.getOffset(),
        req.getLength(), fileLength);
  }

  private void validateInput(final BlockRequest req) {
    Preconditions.checkArgument(req.getOffset() >= 0, "Offset can not be negative: %s",
        req.getOffset());
    Preconditions.checkArgument(req.getLength() >= 0 || req.getLength() == -1,
        "Length can not be negative except -1: %s", req.getLength());
  }
}
