package com.github.highcumontoa.springrequesttracingjava.web;

import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Exposes the actual listening port (needed when {@code server.port=0}). */
@Component
public class LocalPortHolder {

    private volatile int port;

    @EventListener
    public void onWebServerInitialized(ServletWebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    /** Local port, or -1 before the web server has started. */
    public int getPort() {
        return port;
    }
}
