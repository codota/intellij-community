/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.io;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.NotNullProducer;
import com.intellij.util.net.NetUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ide.PooledThreadExecutor;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public class BuiltInServer implements Disposable {
  static final Logger LOG = Logger.getInstance(BuiltInServer.class);

  // Some antiviral software detect viruses by the fact of accessing these ports so we should not touch them to appear innocent.
  private static final int[] FORBIDDEN_PORTS = {6953, 6969, 6970};

  private final ChannelRegistrar channelRegistrar = new ChannelRegistrar();
  private final boolean isOwnerOfEventLoopGroup;

  private final EventLoopGroup eventLoopGroup;
  private final int port;

  public boolean isRunning() {
    return !channelRegistrar.isEmpty();
  }

  private BuiltInServer(@NotNull EventLoopGroup eventLoopGroup, int port, boolean isOwnerOfEventLoopGroup) {
    this.eventLoopGroup = eventLoopGroup;
    this.port = port;
    this.isOwnerOfEventLoopGroup = isOwnerOfEventLoopGroup;
  }

  @NotNull
  public static BuiltInServer start(int workerCount,
                                    int firstPort,
                                    int portsCount,
                                    boolean tryAnyPort,
                                    @Nullable NotNullProducer<ChannelHandler> handler) throws Exception {
    return start(new NioEventLoopGroup(workerCount, PooledThreadExecutor.INSTANCE), true, firstPort, portsCount, tryAnyPort, handler);
  }

  @NotNull
  public static BuiltInServer start(@NotNull EventLoopGroup eventLoopGroup,
                                    boolean isEventLoopGroupOwner,
                                    int firstPort,
                                    int portsCount,
                                    boolean tryAnyPort,
                                    @Nullable NotNullProducer<ChannelHandler> handler) throws Exception {
    ChannelRegistrar channelRegistrar = new ChannelRegistrar();
    ServerBootstrap bootstrap = NettyUtil.nioServerBootstrap(eventLoopGroup);
    configureChildHandler(bootstrap, channelRegistrar, handler);
    return new BuiltInServer(eventLoopGroup, bind(firstPort, portsCount, tryAnyPort, bootstrap, channelRegistrar), isEventLoopGroupOwner);
  }

  public int getPort() {
    return port;
  }

  @NotNull
  public EventLoopGroup getEventLoopGroup() {
    return eventLoopGroup;
  }

  static void configureChildHandler(@NotNull ServerBootstrap bootstrap,
                                    @NotNull final ChannelRegistrar channelRegistrar,
                                    final @Nullable NotNullProducer<ChannelHandler> channelHandler) {
    final PortUnificationServerHandler portUnificationServerHandler = channelHandler == null ? new PortUnificationServerHandler() : null;
    bootstrap.childHandler(new ChannelInitializer() {
      @Override
      protected void initChannel(Channel channel) throws Exception {
        channel.pipeline().addLast(channelRegistrar, channelHandler == null ? portUnificationServerHandler : channelHandler.produce());
      }
    });
  }

  private static int bind(int firstPort,
                          int portsCount,
                          boolean tryAnyPort,
                          @NotNull ServerBootstrap bootstrap,
                          @NotNull ChannelRegistrar channelRegistrar) throws Exception {
    InetAddress address = NetUtils.getLoopbackAddress();

    for (int i = 0; i < portsCount; i++) {
      int port = firstPort + i;

      if (ArrayUtil.indexOf(FORBIDDEN_PORTS, i) >= 0) {
        continue;
      }

      ChannelFuture future = bootstrap.bind(address, port).awaitUninterruptibly();
      if (future.isSuccess()) {
        channelRegistrar.add(future.channel());
        return port;
      }
      else if (!tryAnyPort && i == (portsCount - 1)) {
        ExceptionUtil.rethrowAll(future.cause());
      }
    }

    LOG.info("We cannot bind to our default range, so, try to bind to any free port");
    ChannelFuture future = bootstrap.bind(address, 0).awaitUninterruptibly();
    if (future.isSuccess()) {
      channelRegistrar.add(future.channel());
      return ((InetSocketAddress)future.channel().localAddress()).getPort();
    }
    else {
      ExceptionUtil.rethrowAll(future.cause());
    }

    return -1;  // unreachable
  }

  @Override
  public void dispose() {
    channelRegistrar.close(isOwnerOfEventLoopGroup);
    LOG.info("web server stopped");
  }

  public static void replaceDefaultHandler(@NotNull ChannelHandlerContext context, @NotNull ChannelHandler channelHandler) {
    context.pipeline().replace(DelegatingHttpRequestHandler.class, "replacedDefaultHandler", channelHandler);
  }
}