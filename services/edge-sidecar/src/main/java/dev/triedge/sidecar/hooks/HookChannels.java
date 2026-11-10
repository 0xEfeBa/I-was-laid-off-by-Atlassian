package dev.triedge.sidecar.hooks;

import io.grpc.Attributes;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.jspecify.annotations.Nullable;

public final class HookChannels {

    private HookChannels() {
    }

    public static ManagedChannel create(String target, @Nullable String sourceAddress) {
        NettyChannelBuilder channel = NettyChannelBuilder.forTarget(target).usePlaintext();
        if (sourceAddress != null && !sourceAddress.isBlank()) {
            InetSocketAddress source = new InetSocketAddress(sourceAddress, 0);
            channel.localSocketPicker(new NettyChannelBuilder.LocalSocketPicker() {
                @Override
                public SocketAddress createSocketAddress(SocketAddress remoteAddress, Attributes attrs) {
                    return source;
                }
            });
        }
        return channel.build();
    }
}
