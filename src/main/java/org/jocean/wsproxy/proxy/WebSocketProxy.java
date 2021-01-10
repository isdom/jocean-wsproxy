/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jocean.wsproxy.proxy;

import javax.inject.Inject;

import org.jocean.idiom.BeanHolder;
import org.springframework.beans.factory.annotation.Value;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * An HTTP server which serves Web Socket requests at:
 *
 * http://localhost:8080/websocket
 *
 * Open your browser at <a href="http://localhost:8080/">http://localhost:8080/</a>, then the demo page will be loaded
 * and a Web Socket connection will be made automatically.
 *
 * This server illustrates support for the different web socket specification versions and will work with:
 *
 * <ul>
 * <li>Safari 5+ (draft-ietf-hybi-thewebsocketprotocol-00)
 * <li>Chrome 6-13 (draft-ietf-hybi-thewebsocketprotocol-00)
 * <li>Chrome 14+ (draft-ietf-hybi-thewebsocketprotocol-10)
 * <li>Chrome 16+ (RFC 6455 aka draft-ietf-hybi-thewebsocketprotocol-17)
 * <li>Firefox 7+ (draft-ietf-hybi-thewebsocketprotocol-10)
 * <li>Firefox 11+ (RFC 6455 aka draft-ietf-hybi-thewebsocketprotocol-17)
 * </ul>
 */
public final class WebSocketProxy {

//    static final boolean SSL = System.getProperty("ssl") != null;

    @Value("${wsproxy.port:8080}")
    int proxyPort;

    @Inject
    private BeanHolder _holder;

    EventLoopGroup bossGroup;
    EventLoopGroup proxyGroup;
    Channel binded;

    public void start() throws Exception {
        // Configure SSL.
//        final SslContext sslCtx;
//        if (SSL) {
//            final SelfSignedCertificate ssc = new SelfSignedCertificate();
//            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
//        } else {
//            sslCtx = null;
//        }

        bossGroup = new NioEventLoopGroup(1);
        proxyGroup = new NioEventLoopGroup();

        final ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, proxyGroup)
         .channel(NioServerSocketChannel.class)
         .handler(new LoggingHandler(LogLevel.INFO))
         .childHandler(new WebSocketProxyInitializer(/*sslCtx*/null, _holder));

        binded = b.bind(proxyPort).sync().channel();
    }

    public void stop() {
        try {
            binded.close().sync();
        } catch (final InterruptedException e) {
            bossGroup.shutdownGracefully();
            proxyGroup.shutdownGracefully();
        }
    }
}