/*
 * Copyright 2014 The Netty Project
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

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import java.net.URI;

import org.jocean.idiom.BeanHolder;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.wsproxy.WSNode;
import org.jocean.wsproxy.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.AttributeKey;

/**
 * Handles handshakes and messages
 */
public class WebSocketProxyHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProxyHandler.class);

    private static AttributeKey<WebSocketClient> UPSTREAM = AttributeKey.valueOf("UPSTREAM");

    private static final String WEBSOCKET_PATH = "/wsin/";

    private WebSocketServerHandshaker handshaker;

    private final BeanHolder holder;

    public WebSocketProxyHandler(final BeanHolder holder) {
        this.holder = holder;
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void handleHttpRequest(final ChannelHandlerContext ctx, final FullHttpRequest req) {
        // Handle a bad request.
        if (!req.decoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(req.protocolVersion(), BAD_REQUEST,
                                                                   ctx.alloc().buffer(0)));
            return;
        }

        // Allow only GET methods.
        if (!GET.equals(req.method())) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(req.protocolVersion(), FORBIDDEN,
                                                                   ctx.alloc().buffer(0)));
            return;
        }

        // Send the demo page and favicon.ico
        if ("/".equals(req.uri())) {
            final ByteBuf content = WebSocketProxyIndexPage.getContent(getWebSocketLocation(req));
            final FullHttpResponse res = new DefaultFullHttpResponse(req.protocolVersion(), OK, content);

            res.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
            HttpUtil.setContentLength(res, content.readableBytes());

            sendHttpResponse(ctx, req, res);
            return;
        }

        if ("/favicon.ico".equals(req.uri())) {
            final FullHttpResponse res = new DefaultFullHttpResponse(req.protocolVersion(), NOT_FOUND,
                                                               ctx.alloc().buffer(0));
            sendHttpResponse(ctx, req, res);
            return;
        }

        final String wsuri = getWebSocketURI(req);
        LOG.info("try Handshaker for {}", wsuri);
        // Handshake
        final WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                wsuri, null, true, 5 * 1024 * 1024);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        LOG.info("wsuri:{} handshake complete", wsuri);
                    }
                    final WebSocketClient client = new WebSocketClient();
                    ctx.channel().attr(UPSTREAM).set(client);

                    LOG.info("try find upstream for {}", wsuri);

                    final URI uri = new URI(wsuri);

                    final String path = uri.getPath();
                    final String[] ss = path.split("/");
                    if (ss.length >= 4) {
                        final String host = ss[2];
                        final String srv = ss[3];
                        LOG.info("upstream: {}/{}", host, srv);

                        final WSNode wsnode = holder.getBean("wsnode-" + host + "-" + srv, WSNode.class);
                        LOG.info("found upstream wsnode: {}", wsnode);

                        client.start(new URI(uri.getScheme() + "://" + wsnode.ip + ":" + wsnode.port + path), ctx);
                    } else {
                        LOG.warn("can't found upstream for {}", wsuri);
                    }
                }});
        }
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, final WebSocketFrame frame) {

        // Check for closing frame
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            // TODO
            final WebSocketClient client = ctx.channel().attr(UPSTREAM).get();
            if (null != client) {
                try {
                    client.stop();
                } catch (final InterruptedException e) {
                    LOG.warn("exception when stop client, detail: {}", ExceptionUtils.exception2detail(e));
                }
            }
            return;
        }
        if (frame instanceof PingWebSocketFrame) {
//            ctx.write(new PongWebSocketFrame(frame.content().retain()));
            final WebSocketClient client = ctx.channel().attr(UPSTREAM).get();
            if (null != client) {
                client.send(frame);
            }
            return;
        }
        if (frame instanceof TextWebSocketFrame) {
            // Echo the frame
//            ctx.write(frame.retain());
            final WebSocketClient client = ctx.channel().attr(UPSTREAM).get();
            if (null != client) {
                client.send(frame);
            }
            return;
        }
        if (frame instanceof BinaryWebSocketFrame) {
            // Echo the frame
//            ctx.write(frame.retain());
            final WebSocketClient client = ctx.channel().attr(UPSTREAM).get();
            if (null != client) {
                client.send(frame);
            }
        }
    }

    private static void sendHttpResponse(final ChannelHandlerContext ctx, final FullHttpRequest req, final FullHttpResponse res) {
        // Generate an error page if response getStatus code is not OK (200).
        final HttpResponseStatus responseStatus = res.status();
        if (responseStatus.code() != 200) {
            ByteBufUtil.writeUtf8(res.content(), responseStatus.toString());
            HttpUtil.setContentLength(res, res.content().readableBytes());
        }
        // Send the response and close the connection if necessary.
        final boolean keepAlive = HttpUtil.isKeepAlive(req) && responseStatus.code() == 200;
        HttpUtil.setKeepAlive(res, keepAlive);
        final ChannelFuture future = ctx.write(res); // Flushed in channelReadComplete()
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private static String getWebSocketLocation(final FullHttpRequest req) {
        final String location =  req.headers().get(HttpHeaderNames.HOST) + WEBSOCKET_PATH;
//        if (WebSocketProxy.SSL) {
//            return "wss://" + location;
//        } else {
            return "ws://" + location;
//        }
    }

    private String getWebSocketURI(final FullHttpRequest req) {
        final String uri =  req.headers().get(HttpHeaderNames.HOST) + req.uri();
        return "ws://" + uri;
    }

}