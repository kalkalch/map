package com.map.sstp;

import com.map.util.LocalSettings;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans a minimal VPN routing model for SSTP.
 * Only the remote proxy endpoint should be routed into the tunnel.
 */
public final class SstpRoutePlanner {

    private SstpRoutePlanner() {}

    public static Plan plan(LocalSettings settings) throws UnknownHostException {
        List<ProxyEndpoint> remoteProxies = extractRemoteProxies(settings);
        if (remoteProxies.isEmpty()) {
            return new Plan(false, Collections.emptyList(), Collections.emptyList(), "Не задан удаленный прокси");
        }

        return plan(remoteProxies);
    }

    public static Plan plan(String remoteHost, int remotePort) throws UnknownHostException {
        if (remoteHost == null || remoteHost.isBlank() || remotePort < 1 || remotePort > 65535) {
            return new Plan(false, Collections.emptyList(), Collections.emptyList(), "Не задан удаленный прокси");
        }

        List<ProxyEndpoint> remoteProxies = new ArrayList<>();
        remoteProxies.add(new ProxyEndpoint(remoteHost, remotePort));
        return plan(remoteProxies);
    }

    private static Plan plan(List<ProxyEndpoint> remoteProxies) throws UnknownHostException {
        Map<String, RouteEntry> deduplicatedRoutes = new LinkedHashMap<>();
        for (ProxyEndpoint remoteProxy : remoteProxies) {
            String routeAddress = resolveRouteAddress(remoteProxy.getHost());
            int routePrefix = routeAddress.contains(":") ? 128 : 32;
            String routeKey = routeAddress + "/" + routePrefix;
            deduplicatedRoutes.putIfAbsent(routeKey, new RouteEntry(routeAddress, routePrefix, remoteProxy));
        }

        String detail = deduplicatedRoutes.size() == 1
            ? "Маршрут только до удаленного прокси"
            : "Маршруты до удаленных прокси";
        return new Plan(true, new ArrayList<>(deduplicatedRoutes.values()), remoteProxies, detail);
    }

    private static List<ProxyEndpoint> extractRemoteProxies(LocalSettings settings) {
        List<ProxyEndpoint> proxies = new ArrayList<>();
        if (settings.isSocks5Enabled() && settings.isSocks5UpstreamEnabled() && !settings.getSocks5UpstreamHost().isBlank()) {
            proxies.add(new ProxyEndpoint(settings.getSocks5UpstreamHost(), settings.getSocks5UpstreamPort()));
        }
        if (settings.isHttpEnabled() && settings.isHttpUpstreamEnabled() && !settings.getHttpUpstreamHost().isBlank()) {
            proxies.add(new ProxyEndpoint(settings.getHttpUpstreamHost(), settings.getHttpUpstreamPort()));
        }
        return proxies;
    }

    private static String resolveRouteAddress(String host) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(host);
        return address.getHostAddress();
    }

    public static final class Plan {
        private final boolean valid;
        private final List<RouteEntry> routes;
        private final List<ProxyEndpoint> remoteProxies;
        private final String detail;

        public Plan(boolean valid, List<RouteEntry> routes, List<ProxyEndpoint> remoteProxies, String detail) {
            this.valid = valid;
            this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
            this.remoteProxies = Collections.unmodifiableList(new ArrayList<>(remoteProxies));
            this.detail = detail;
        }

        public boolean isValid() {
            return valid;
        }

        public String getRouteAddress() {
            return routes.isEmpty() ? null : routes.get(0).getRouteAddress();
        }

        public int getRoutePrefix() {
            return routes.isEmpty() ? 0 : routes.get(0).getRoutePrefix();
        }

        public ProxyEndpoint getRemoteProxy() {
            return remoteProxies.isEmpty() ? null : remoteProxies.get(0);
        }

        public List<RouteEntry> getRoutes() {
            return routes;
        }

        public List<ProxyEndpoint> getRemoteProxies() {
            return remoteProxies;
        }

        public String getDetail() {
            return detail;
        }
    }

    public static final class RouteEntry {
        private final String routeAddress;
        private final int routePrefix;
        private final ProxyEndpoint remoteProxy;

        public RouteEntry(String routeAddress, int routePrefix, ProxyEndpoint remoteProxy) {
            this.routeAddress = routeAddress;
            this.routePrefix = routePrefix;
            this.remoteProxy = remoteProxy;
        }

        public String getRouteAddress() {
            return routeAddress;
        }

        public int getRoutePrefix() {
            return routePrefix;
        }

        public ProxyEndpoint getRemoteProxy() {
            return remoteProxy;
        }
    }

    public static final class ProxyEndpoint {
        private final String host;
        private final int port;

        public ProxyEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }
    }
}
