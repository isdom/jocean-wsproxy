package org.jocean.wsproxy;

import org.springframework.beans.factory.annotation.Value;

public class WSNode {

    @Value("${hostname}")
    public String hostname;

    @Value("${service}")
    public String service;

    @Value("${ip}")
    public String ip;

    @Value("${port}")
    public int port;

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();
        builder.append("WSNode [hostname=").append(hostname).append(", service=").append(service).append(", ip=")
                .append(ip).append(", port=").append(port).append("]");
        return builder.toString();
    }
}
