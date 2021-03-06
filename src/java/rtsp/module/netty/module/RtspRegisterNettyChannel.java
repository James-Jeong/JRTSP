package rtsp.module.netty.module;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rtsp.config.ConfigManager;
import rtsp.module.netty.handler.RtspRegisterChannelHandler;
import rtsp.protocol.register.base.URtspMessage;
import rtsp.service.AppInstance;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

public class RtspRegisterNettyChannel {

    private static final Logger logger = LoggerFactory.getLogger(RtspRegisterNettyChannel.class);

    private final String ip;
    private final int port;

    private Channel channel = null;
    private Bootstrap bootstrap;

    ////////////////////////////////////////////////////////////////////////////////

    public RtspRegisterNettyChannel(String ip, int port) {
        this.ip = ip;
        this.port = port;
    }

    ////////////////////////////////////////////////////////////////////////////////

    public void run () {
        bootstrap = new Bootstrap();
        ConfigManager configManager = AppInstance.getInstance().getConfigManager();
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup(configManager.getStreamThreadPoolSize());

        bootstrap.group(eventLoopGroup)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, false)
                .option(ChannelOption.SO_SNDBUF, configManager.getSendBufSize())
                .option(ChannelOption.SO_RCVBUF, configManager.getRecvBufSize())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    public void initChannel (final NioDatagramChannel ch) {
                        final ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new RtspRegisterChannelHandler(ip, port));
                    }
                });
    }

    public void start() {
        if (channel != null) {
            return;
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            logger.warn("UnknownHostException is occurred. (ip={})", ip, e);
            return;
        }

        try {
            ChannelFuture channelFuture = bootstrap.bind(address, port).sync();
            if (channelFuture == null) {
                logger.warn("Fail to start the rtsp register channel. (ip={}, port={})", ip, port);
                return;
            }

            channel = channelFuture.channel();
            logger.debug("Success to start the rtsp register channel. (ip={}, port={})", ip, port);
        } catch (Exception e) {
            logger.warn("Fail to start the rtsp register channel. (ip={}, port={})", ip, port);
        }
    }

    public void stop() {
        if (channel == null) {
            logger.warn("Fail to stop the rtsp register channel. (ip={}, port={})", ip, port);
            return;
        }

        channel.close();
        channel = null;
        logger.debug("Success to stop the rtsp register channel. (ip={}, port={})", ip, port);
    }

    public void sendResponse(String targetIp, short targetPort, URtspMessage uRtspMessage) {
        if (targetIp == null || targetPort <= 0 || uRtspMessage == null) {
            return;
        }

        InetSocketAddress remoteAddress = new InetSocketAddress(targetIp, targetPort);
        channel.writeAndFlush(
                new DatagramPacket(
                        Unpooled.copiedBuffer(uRtspMessage.getByteData()),
                        remoteAddress
                )
        );

        logger.debug("[<] {} ({})", uRtspMessage, uRtspMessage.getByteData().length);
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    public Channel getChannel() {
        return channel;
    }
}
