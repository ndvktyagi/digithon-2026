package com.picopilot.common.net;

import com.picopilot.common.exception.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Guards outbound connector connections to tenant-supplied hosts. In the SaaS deployment
 * private/loopback targets must be blocked so tenants cannot probe internal services;
 * local development keeps them allowed (picopilot.connectors.allow-private-hosts).
 */
@Component
public class HostPolicy {

    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");

    private final boolean allowPrivateHosts;

    public HostPolicy(@Value("${picopilot.connectors.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public void requireAllowed(String host) {
        if (host == null || !HOSTNAME.matcher(host).matches()) {
            throw new ValidationException("Invalid host name");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ValidationException("Unknown host: " + host);
        }
        if (allowPrivateHosts) {
            return;
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                throw new ValidationException("Connections to private or local network hosts are not allowed");
            }
        }
    }
}
