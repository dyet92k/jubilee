package org.jruby.jubilee;

import io.netty.handler.codec.http.HttpHeaders;
import org.jruby.*;

import org.jruby.jubilee.utils.RubyHelper;
import org.jruby.runtime.*;
import org.jruby.runtime.builtin.IRubyObject;
import org.vertx.java.core.MultiMap;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.HttpVersion;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class RackEnvironment {

    // When adding a key to the enum be sure to add its RubyString equivalent
    // to populateRackKeyMap below
    static enum RACK_KEY {
        RACK_INPUT, RACK_ERRORS, REQUEST_METHOD, SCRIPT_NAME,
        PATH_INFO, QUERY_STRING, SERVER_NAME, SERVER_PORT,
        CONTENT_TYPE, REQUEST_URI, REMOTE_ADDR, URL_SCHEME,
        VERSION, MULTITHREAD, MULTIPROCESS, RUN_ONCE, CONTENT_LENGTH,
        HTTPS, HTTP_VERSION, HIJACK_P, HIJACK//, HIJACK_IO
    }

    static final int NUM_RACK_KEYS = RACK_KEY.values().length;

    public RackEnvironment(final Ruby runtime) throws IOException {
        this.runtime = runtime;
        this.netSocketClass = (RubyClass) runtime.getClassFromPath("Jubilee::NetSocket");
        rackVersion = RubyArray.newArray(runtime, RubyFixnum.one(runtime), RubyFixnum.four(runtime));
        errors = new RubyIO(runtime, runtime.getErr());
        errors.setAutoclose(false);

        populateRackKeyMap();
    }

    private void populateRackKeyMap() {
        putRack("rack.input", RACK_KEY.RACK_INPUT);
        putRack("rack.errors", RACK_KEY.RACK_ERRORS);
        putRack("REQUEST_METHOD", RACK_KEY.REQUEST_METHOD);
        putRack("SCRIPT_NAME", RACK_KEY.SCRIPT_NAME);
        putRack("PATH_INFO", RACK_KEY.PATH_INFO);
        putRack("QUERY_STRING", RACK_KEY.QUERY_STRING);
        putRack("SERVER_NAME", RACK_KEY.SERVER_NAME);
        putRack("SERVER_PORT", RACK_KEY.SERVER_PORT);
        putRack("HTTP_VERSION", RACK_KEY.HTTP_VERSION);
        putRack("CONTENT_TYPE", RACK_KEY.CONTENT_TYPE);
        putRack("REQUEST_URI", RACK_KEY.REQUEST_URI);
        putRack("REMOTE_ADDR", RACK_KEY.REMOTE_ADDR);
        putRack("rack.url_scheme", RACK_KEY.URL_SCHEME);
        putRack("rack.version", RACK_KEY.VERSION);
        putRack("rack.multithread", RACK_KEY.MULTITHREAD);
        putRack("rack.multiprocess", RACK_KEY.MULTIPROCESS);
        putRack("rack.run_once", RACK_KEY.RUN_ONCE);
        putRack("rack.hijack?", RACK_KEY.HIJACK_P);
        putRack("rack.hijack", RACK_KEY.HIJACK);
        // putRack("rack.hijack_io", RACK_KEY.HIJACK_IO); Don't have to be lazy, since once rack.hijack is called, the io
        // object has to be required by the caller.
        putRack("CONTENT_LENGTH", RACK_KEY.CONTENT_LENGTH);
        putRack("HTTPS", RACK_KEY.HTTPS);
    }

    private void putRack(String key, RACK_KEY value) {
        rackKeyMap.put(RubyHelper.toUsAsciiRubyString(runtime, key), value);
    }

    public RubyHash getEnv(final HttpServerRequest request,
                           final RackInput input,
                           final boolean isSSL) throws IOException {
        // XXX
        String contextPath = "/";
        MultiMap headers = request.headers();
        // TODO: Should we only use this faster RackEnvironmentHash if we detect
        // specific JRuby versions that we know are compatible?
        final RackEnvironmentHash env = new RackEnvironmentHash(runtime, headers, rackKeyMap);
        env.lazyPut(RACK_KEY.RACK_INPUT, input, false);
        env.lazyPut(RACK_KEY.RACK_ERRORS, errors, false);

        // Don't use request.getPathInfo because that gets decoded by the container
        String pathInfo = request.path();

        // strip contextPath and servletPath from pathInfo
        if (pathInfo.startsWith(contextPath) && !contextPath.equals("/")) {
            pathInfo = pathInfo.substring(contextPath.length());
        }

        String scriptName = contextPath;
        // SCRIPT_NAME should be an empty string for the root
        if (scriptName.equals("/")) {
            scriptName = "";
        }

        env.lazyPut(RACK_KEY.REQUEST_METHOD, request.method(), true);
        env.lazyPut(RACK_KEY.SCRIPT_NAME, scriptName, false);
        env.lazyPut(RACK_KEY.PATH_INFO, pathInfo, false);
        env.lazyPut(RACK_KEY.QUERY_STRING, orEmpty(request.query()), false);
        env.lazyPut(RACK_KEY.SERVER_NAME, Const.LOCALHOST, false);
        env.lazyPut(RACK_KEY.SERVER_PORT, Const.PORT_80, true);
        env.lazyPut(RACK_KEY.HTTP_VERSION,
                request.version() == HttpVersion.HTTP_1_1 ? Const.HTTP_11 : Const.HTTP_10, true);
        env.lazyPut(RACK_KEY.CONTENT_TYPE, headers.get(HttpHeaders.Names.CONTENT_TYPE), true);
        env.lazyPut(RACK_KEY.REQUEST_URI, scriptName + pathInfo, false);
        env.lazyPut(RACK_KEY.REMOTE_ADDR, getRemoteAddr(request), true);
        env.lazyPut(RACK_KEY.URL_SCHEME, isSSL? Const.HTTPS : Const.HTTP, true);
        env.lazyPut(RACK_KEY.VERSION, rackVersion, false);
        env.lazyPut(RACK_KEY.MULTITHREAD, runtime.getTrue(), false);
        env.lazyPut(RACK_KEY.MULTIPROCESS, runtime.getFalse(), false);
        env.lazyPut(RACK_KEY.RUN_ONCE, runtime.getFalse(), false);
        env.lazyPut(RACK_KEY.HIJACK_P, runtime.getTrue(), false);
        env.lazyPut(RACK_KEY.HIJACK, new RubyCallable.Callable() {
          @Override
          public void call() {
            env.put("rack.hijack_io", new RubyNetSocket(runtime, netSocketClass, request.netSocket()));
          }
        }, false);


        final int contentLength = getContentLength(headers);
        if (contentLength >= 0) {
            env.lazyPut(RACK_KEY.CONTENT_LENGTH, contentLength + "", true);
        }

        if (isSSL) {
            env.lazyPut(RACK_KEY.HTTPS, "on", true);
        }

        return env;
    }

    private static String getRemoteAddr(final HttpServerRequest request) {
        InetSocketAddress sourceAddress = request.remoteAddress();
        if(sourceAddress == null) {
            return "";
        }
        InetAddress address = sourceAddress.getAddress();
        if(address == null) {
            return "";
        }
        return address.getHostAddress();
    }

    private static int getContentLength(final MultiMap headers) {
        final String contentLengthStr = headers.get(HttpHeaders.Names.CONTENT_LENGTH);
        if (contentLengthStr == null || contentLengthStr.isEmpty()) {
            return -1;
        }
        return Integer.parseInt(contentLengthStr);
    }

    private String orEmpty(String val) {
        return val == null ? "" : val;
    }

    private final Ruby runtime;
    private final RubyArray rackVersion;
    private final RubyIO errors;
    private final Map<RubyString, RACK_KEY> rackKeyMap = new HashMap<>();
    private final RubyClass netSocketClass;
}