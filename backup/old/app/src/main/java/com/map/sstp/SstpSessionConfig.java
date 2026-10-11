package com.map.sstp;

public class SstpSessionConfig {
    private final String serverHost;
    private final int serverPort;
    private final String username;
    private final String password;
    private final boolean ignoreCertificateErrors;
    private final String routeAddress;
    private final int routePrefix;
    private final String sessionName;

    public SstpSessionConfig(
        String serverHost,
        int serverPort,
        String username,
        String password,
        boolean ignoreCertificateErrors,
        String routeAddress,
        int routePrefix,
        String sessionName
    ) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.username = username;
        this.password = password;
        this.ignoreCertificateErrors = ignoreCertificateErrors;
        this.routeAddress = routeAddress;
        this.routePrefix = routePrefix;
        this.sessionName = sessionName;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isIgnoreCertificateErrors() {
        return ignoreCertificateErrors;
    }

    public String getRouteAddress() {
        return routeAddress;
    }

    public int getRoutePrefix() {
        return routePrefix;
    }

    public String getSessionName() {
        return sessionName;
    }
}
