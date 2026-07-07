import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import com.nezhahq.agent.DohResolver;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class App {
    
    private static String UUID;
    private static String NEZHA_SERVER;
    private static String NEZHA_PORT;
    private static String NEZHA_KEY;
    private static String DOMAIN;
    private static String SUB_PATH;
    private static String NAME;
    private static String WSPATH;
    private static int PORT;
    private static boolean AUTO_ACCESS;
    private static boolean DEBUG;
    private static boolean DOH_ENABLED;
    private static String DOH_ENDPOINTS;
    private static boolean TUNNEL_ENABLED;
    private static String TUNNEL_TOKEN;
    private static String TUNNEL_DOMAIN;
    private static int TUNNEL_PORT;

    private static com.nezhahq.agent.NezhaJavaAgent.RunningAgent nezhaAgent;
    private static AutoCloseable tunnelRuntime;

    private static String PROTOCOL_UUID;
    private static byte[] UUID_BYTES;
    
    private static String currentDomain;
    private static int currentPort = 443;
    private static String tls = "tls";
    private static String isp = "Unknown";
    
    private static final List<String> BLOCKED_DOMAINS = Arrays.asList(
            "speedtest.net", "fast.com", "speedtest.cn", "speed.cloudflare.com", 
            "speedof.me", "testmy.net", "bandwidth.place", "speed.io", 
            "librespeed.org", "speedcheck.org");
    private static final List<String> TLS_PORTS = Arrays.asList(
            "443", "8443", "2096", "2087", "2083", "2053");
    private static final List<String> PUBLIC_IP_ENDPOINTS = Arrays.asList(
            "https://api-ipv4.ip.sb/ip",
            "https://api.ipify.org",
            "https://ipv4.icanhazip.com",
            "https://checkip.amazonaws.com");

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Map<String, String> dnsCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> dnsCacheTime = new ConcurrentHashMap<>();
    private static final long DNS_CACHE_TTL = 300000;
    
    // 日志级别控制
    private static boolean SILENT_MODE = true; 
    
    private static void log(String level, String msg) {
        if (SILENT_MODE && !level.equals("INFO")) return;  
        System.out.println(new Date() + " - " + level + " - " + msg);
    }
    
    private static void info(String msg) {
        if (!msg.startsWith("public IP:") && !DEBUG) return;
        log("INFO", msg);
    }
    private static void error(String msg) { log("ERROR", msg); }
    private static void error(String msg, Throwable t) { 
        log("ERROR", msg);
        if (DEBUG) t.printStackTrace();
    }
    private static void debug(String msg) { if (DEBUG) log("DEBUG", msg); }
    
    private static void loadConfig() {
        UUID = HardcodedConfig.UUID;
        NEZHA_SERVER = HardcodedConfig.NEZHA_SERVER;
        NEZHA_PORT = HardcodedConfig.NEZHA_PORT;
        NEZHA_KEY = HardcodedConfig.NEZHA_KEY;
        DOMAIN = HardcodedConfig.DOMAIN;
        SUB_PATH = HardcodedConfig.SUB_PATH;
        NAME = HardcodedConfig.NAME;
        WSPATH = HardcodedConfig.WSPATH.isBlank() ? UUID.substring(0, 8) : HardcodedConfig.WSPATH;
        PORT = configuredPort();
        AUTO_ACCESS = HardcodedConfig.AUTO_ACCESS;
        DEBUG = HardcodedConfig.DEBUG;
        DOH_ENABLED = HardcodedConfig.DOH_ENABLED;
        DOH_ENDPOINTS = HardcodedConfig.DOH_ENDPOINTS;
        TUNNEL_ENABLED = HardcodedConfig.TUNNEL_ENABLED;
        TUNNEL_TOKEN = HardcodedConfig.TUNNEL_TOKEN;
        TUNNEL_DOMAIN = HardcodedConfig.TUNNEL_DOMAIN;
        TUNNEL_PORT = HardcodedConfig.TUNNEL_PORT;

        PROTOCOL_UUID = UUID.replace("-", "");
        UUID_BYTES = hexStringToByteArray(PROTOCOL_UUID);
        currentDomain = DOMAIN;
        SILENT_MODE = !DEBUG;
    }

    private static int configuredPort() {
        int hardcoded = HardcodedConfig.PORT;
        if (hardcoded != 0) return hardcoded;

        // 尝试自动获取面板注入的环境变量（例如 Serv00, Pterodactyl, Heroku 常用 PORT 或 SERVER_PORT）
        String envPort = System.getenv("PORT");
        if (envPort == null || envPort.isBlank()) {
            envPort = System.getenv("SERVER_PORT");
        }
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ignored) {}
        }

        return 0; // 面板没给端口环境变量的话，依然 fallback 到 0（随机分配）
    }

    private static Integer parsePort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static boolean isPortAvailable(int port) {
        try (var socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int findAvailablePort(int startPort) {
        int firstPort = startPort == 0 ? 1024 : startPort;
        int lastPort = Math.min(65535, firstPort + 99);
        for (int port = firstPort; port <= lastPort; port++) {
            if (isPortAvailable(port)) return port;
        }
        throw new RuntimeException("No available ports found");
    }
    
    static boolean isBlockedDomain(String host) {
        if (host == null || host.isEmpty()) return false;
        String hostLower = host.toLowerCase();
        return BLOCKED_DOMAINS.stream().anyMatch(blocked -> 
                hostLower.equals(blocked) || hostLower.endsWith("." + blocked));
    }
    
    static String resolveHost(String host) {
        if (host == null || host.isBlank() || DohResolver.isIpLiteral(host)) {
            return host;
        }
        String cached = dnsCache.get(host);
        Long time = dnsCacheTime.get(host);
        if (cached != null && time != null && System.currentTimeMillis() - time < DNS_CACHE_TTL) {
            return cached;
        }
        if (DOH_ENABLED) {
            Optional<InetAddress> dohAddress = DohResolver.resolveFirst(host, DOH_ENDPOINTS);
            if (dohAddress.isPresent()) {
                String ip = dohAddress.get().getHostAddress();
                dnsCache.put(host, ip);
                dnsCacheTime.put(host, System.currentTimeMillis());
                return ip;
            }
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            String ip = address.getHostAddress();
            dnsCache.put(host, ip);
            dnsCacheTime.put(host, System.currentTimeMillis());
            return ip;
        } catch (Exception ex) {
            error("DNS resolution failed for: " + host);
            return host;
        }
    }
    
    private static void getIp() {
        if (DOMAIN == null || DOMAIN.isEmpty() || DOMAIN.equals("your-domain.com")) {
            if (DOH_ENABLED && DOH_ENDPOINTS != null && !DOH_ENDPOINTS.isBlank()) {
                Optional<String> dohIp = DohResolver.resolvePublicIpv4(DOH_ENDPOINTS);
                if (dohIp.isPresent()) {
                    currentDomain = dohIp.get();
                    tls = "none";
                    currentPort = PORT;
                    info("public IP: " + currentDomain);
                    return;
                }
                debug("DoH public IP lookup failed");
            }
            String lastError = null;
            for (String endpoint : PUBLIC_IP_ENDPOINTS) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(5))
                            .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        String ip = response.body().trim();
                        if (!ip.isEmpty()) {
                            currentDomain = ip;
                            tls = "none";
                            currentPort = PORT;
                            info("public IP: " + currentDomain);
                            return;
                        }
                    }
                    lastError = endpoint + " returned HTTP " + response.statusCode();
                } catch (Exception e) {
                    lastError = endpoint + " failed: " + e.getMessage();
                    debug(lastError);
                }
            }
            error("Failed to get IP" + (lastError == null ? "" : ": " + lastError));
            currentDomain = "change-your-domain.com";
            tls = "tls";
            currentPort = 443;
        } else {
            currentDomain = DOMAIN;
            tls = "tls";
            currentPort = 443;
        }
    }
    
    private static void getIsp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ip.sb/geoip"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String countryCode = extractJsonValue(body, "country_code");
                String ispName = extractJsonValue(body, "isp");
                isp = countryCode + "-" + ispName;
                isp = isp.replace(" ", "_");
                // info("Got ISP info: " + isp);
                return;
            }
        } catch (Exception e) {
            debug("Failed to get ISP from ip.sb: " + e.getMessage());
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String countryCode = extractJsonValue(body, "countryCode");
                String org = extractJsonValue(body, "org");
                isp = countryCode + "-" + org;
                isp = isp.replace(" ", "_");
                info("Got ISP info: " + isp);
            }
        } catch (Exception e) {
            debug("Failed to get ISP from ip-api: " + e.getMessage());
        }
    }
    
    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    private static void startNezha() {
        if (NEZHA_SERVER.isEmpty() || NEZHA_KEY.isEmpty()) {
            return;
        }
        try {
            nezhaAgent = NezhaAgentBridge.start(NEZHA_SERVER, NEZHA_PORT, NEZHA_KEY, UUID, DEBUG, DOH_ENABLED, DOH_ENDPOINTS);
            info("✅ Nezha Java Agent started successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Nezha Java Agent", e);
        }
    }

    private static void cleanupNezha() {
        if (nezhaAgent != null) {
            try {
                nezhaAgent.stop();
            } finally {
                nezhaAgent = null;
            }
        }
    }

    private static void startTunnel(int targetPort) {
        if (!TUNNEL_ENABLED || TUNNEL_TOKEN == null || TUNNEL_TOKEN.isBlank()) {
            return;
        }
        info("Starting CF tunnel routing to local port " + targetPort);
        tunnelRuntime = TunnelSupport.start(null, TUNNEL_TOKEN, "127.0.0.1", targetPort, WSPATH, DEBUG);
        if (tunnelRuntime != null) {
            info("✅ CF Tunnel client started");
        } else {
            debug("tunnel module not available or invalid token");
        }
    }

    private static void cleanupTunnel() {
        if (tunnelRuntime != null) {
            try {
                tunnelRuntime.close();
            } catch (Exception ignored) {
            } finally {
                tunnelRuntime = null;
            }
        }
    }

    private static void addAccessTask() {
        if (!AUTO_ACCESS || DOMAIN.isEmpty()) return;
        
        String fullUrl = "https://" + DOMAIN + "/" + SUB_PATH;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oooo.serv00.net/add-url"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + fullUrl + "\"}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            info("Automatic Access Task added successfully");
        } catch (Exception e) {
            debug("Failed to add access task: " + e.getMessage());
        }
    }
    
    private static String generateSubscription() {
        String namePart = NAME.isEmpty() ? isp : NAME + "-" + isp;
        String vlessUrl = String.format(
                "vless://%s@%s:%d?encryption=none&security=%s&sni=%s&fp=chrome&type=ws&host=%s&path=%%2F%s#%s",
                UUID, currentDomain, currentPort, tls, currentDomain, currentDomain, WSPATH, namePart);

        String trojanUrl = String.format(
                "trojan://%s@%s:%d?security=%s&sni=%s&fp=chrome&type=ws&host=%s&path=%%2F%s#%s",
                UUID, currentDomain, currentPort, tls, currentDomain, currentDomain, WSPATH, namePart);

        String ssMethodPassword = Base64.getEncoder().encodeToString(("none:" + UUID).getBytes(StandardCharsets.UTF_8));
        String ssTlsParam = "tls".equals(tls) ? "tls;" : "";
        String ssUrl = String.format(
                "ss://%s@%s:%d?plugin=v2ray-plugin;mode%%3Dwebsocket;host%%3D%s;path%%3D%%2F%s;%ssni%%3D%s;skip-cert-verify%%3Dtrue;mux%%3D0#%s",
                ssMethodPassword, currentDomain, currentPort, currentDomain, WSPATH, ssTlsParam, currentDomain, namePart);

        String tunnelUrl = null;
        if (TUNNEL_ENABLED && TUNNEL_DOMAIN != null && !TUNNEL_DOMAIN.isBlank()) {
            String tunnelLabel = namePart + "-tunnel";
            tunnelUrl = TunnelSupport.buildSubscriptionLine(true, UUID, TUNNEL_DOMAIN, 443, WSPATH, tunnelLabel);
        }

        return SubscriptionComposer.build(vlessUrl, trojanUrl, ssUrl, tunnelUrl);
    }

    public static void main(String[] args) {
        try {
            io.grpc.LoadBalancerRegistry.getDefaultRegistry().register(new io.grpc.internal.PickFirstLoadBalancerProvider());
            io.grpc.NameResolverRegistry.getDefaultRegistry().register(new io.grpc.internal.DnsNameResolverProvider());
        } catch (Throwable ignored) {
        }
        tuneRuntimeDefaults();
        normalizeUserHome();
        loadConfig();
        runWebSocketServer();
    }

    private static void tuneRuntimeDefaults() {
        setDefaultProperty("io.netty.eventLoopThreads", "2");
        setDefaultProperty("io.netty.allocator.numHeapArenas", "2");
        setDefaultProperty("io.netty.allocator.numDirectArenas", "2");
        setDefaultProperty("io.netty.noPreferDirect", "true");
    }

    private static void setDefaultProperty(String key, String value) {
        if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
            System.setProperty(key, value);
        }
    }

    private static void normalizeUserHome() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank() || "?".equals(home.trim()) || !Files.isDirectory(Path.of(home))) {
            String workingDir = System.getProperty("user.dir", ".");
            System.setProperty("user.home", Path.of(workingDir).toAbsolutePath().normalize().toString());
        }
    }

    private static void runSocks5Server() {
        info("Starting SOCKS5 Server...");
        info("Subscription Path: /" + SUB_PATH);
        
        getIp();
        startNezha();
        addAccessTask();

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(2);
        
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new IdleStateHandler(300, 0, 0));
                            p.addLast(new Socks5AuthHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            Channel ch = b.bind("0.0.0.0", PORT).sync().channel();
            int actualPort = ((java.net.InetSocketAddress) ch.localAddress()).getPort();
            currentPort = actualPort;

            info("✅ SOCKS5 server is running on port " + actualPort);
            startTunnel(actualPort);
            
            ch.closeFuture().sync();
            
        } catch (InterruptedException e) {
            error("Server interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            error("Server error", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            cleanupTunnel();
            cleanupNezha();
            info("Server stopped");
        }
    }

    /** SOCKS5 认证 + CONNECT 处理器 */
    static class Socks5AuthHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private enum Stage { NEGOTIATE, AUTH, CONNECT_REQ, ESTABLISHED }
        private Stage stage = Stage.NEGOTIATE;
        private Channel outboundChannel;
        private final byte[] buf = new byte[256];
        private int pos = 0;
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            if (!in.isReadable()) return;
            switch (stage) {
                case NEGOTIATE: handleNegotiate(ctx, in); break;
                case AUTH: handleAuth(ctx, in); break;
                case CONNECT_REQ: handleConnectReq(ctx, in); break;
                case ESTABLISHED: handleTunnel(ctx, in); break;
            }
        }
        
        private void handleNegotiate(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            // 解析: VER(1) + NMETHODS(1) + METHODS(N)
            buf[pos++] = in.readByte();
            if (pos < 2) return;
            int nMethods = buf[1] & 0xFF;
            while (pos < 2 + nMethods && in.isReadable()) buf[pos++] = in.readByte();
            if (pos < 2 + nMethods) return;
            
            if (buf[0] != 0x05) { ctx.close(); return; }
            
            // 检查是否支持 username/password (method 0x02)
            boolean authSupported = false;
            for (int i = 2; i < pos; i++) {
                if (buf[i] == 0x02) { authSupported = true; break; }
            }
            
            ByteBuf resp = Unpooled.buffer(2);
            resp.writeByte(0x05);
            resp.writeByte(authSupported ? (byte)0x02 : (byte)0x00);
            ctx.writeAndFlush(resp);
            
            if (authSupported) {
                stage = Stage.AUTH;
                pos = 0;
            } else {
                // 无认证模式，直接读 CONNECT 请求
                stage = Stage.CONNECT_REQ;
                pos = 0;
            }
        }
        
        private void handleAuth(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            // 解析: VER(1) + ULEN(1) + USERNAME(ULEN) + PLEN(1) + PASSWORD(PLEN)
            buf[pos++] = in.readByte();
            if (pos < 2) return;
            int ulen = buf[1] & 0xFF;
            while (pos < 2 + ulen && in.isReadable()) buf[pos++] = in.readByte();
            if (pos < 2 + ulen) return;
            buf[pos++] = in.readByte(); // PLEN
            int plen = buf[3 + ulen] & 0xFF;
            while (pos < 4 + ulen + plen && in.isReadable()) buf[pos++] = in.readByte();
            if (pos < 4 + ulen + plen) return;
            
            String username = new String(buf, 2, ulen, StandardCharsets.UTF_8);
            String password = new String(buf, 4 + ulen, plen, StandardCharsets.UTF_8);
            
            if (username.equals("socks5") && password.equals("socks5")) {
                ByteBuf resp = Unpooled.buffer(2);
                resp.writeByte(0x01); resp.writeByte(0x00);
                ctx.writeAndFlush(resp);
                stage = Stage.CONNECT_REQ;
                pos = 0;
            } else {
                ByteBuf resp = Unpooled.buffer(2);
                resp.writeByte(0x01); resp.writeByte(0x01);
                ctx.writeAndFlush(resp);
                ctx.close();
            }
        }
        
        private void handleConnectReq(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            // 解析: VER(1) CMD(1) RSV(1) ATYP(1) + DST.ADDR + DST.PORT
            while (pos < 10 && in.isReadable()) buf[pos++] = in.readByte();
            if (pos < 10) return;
            
            if (buf[0] != 0x05 || buf[1] != 0x01) { ctx.close(); return; }
            
            int offset = 4;
            String host;
            if (buf[3] == 0x01) { // IPv4
                if (pos < 10) return;
                host = (buf[offset]&0xFF)+"."+((buf[offset+1]&0xFF))+"."+((buf[offset+2]&0xFF))+"."+((buf[offset+3]&0xFF));
                offset += 4;
            } else if (buf[3] == 0x03) { // Domain
                int hlen = buf[offset] & 0xFF; offset++;
                if (pos < offset + hlen + 2) return;
                host = new String(buf, offset, hlen, StandardCharsets.UTF_8);
                offset += hlen;
            } else if (buf[3] == 0x04) { // IPv6
                if (pos < offset + 16 + 2) return;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02x%02x", buf[offset+i], buf[offset+i+1]));
                }
                host = sb.toString(); offset += 16;
            } else { ctx.close(); return; }
            
            int port = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
            
            if (isBlockedDomain(host)) { ctx.close(); return; }
            
            // 发送 CONNECT 成功响应
            ByteBuf reply = Unpooled.buffer(10);
            reply.writeByte(0x05); reply.writeByte(0x00); reply.writeByte(0x00);
            reply.writeByte(0x01); reply.writeByte(0x00); reply.writeByte(0x00);
            reply.writeByte(0x00); reply.writeByte(0x00); reply.writeByte(0x00); reply.writeByte(0x00);
            ctx.writeAndFlush(reply);
            
            connectToTarget(ctx, host, port);
        }
        
        private void connectToTarget(ChannelHandlerContext ctx, String host, int port) {
            String resolvedHost = resolveHost(host);
            Bootstrap b = new Bootstrap();
            b.group(ctx.channel().eventLoop())
                    .channel(ctx.channel().getClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new Socks5Forwarder(ch));
                        }
                    });
            ChannelFuture f = b.connect(resolvedHost, port);
            outboundChannel = f.channel();
            f.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    debug("SOCKS5 connect failed: " + future.cause().getMessage());
                    ctx.close();
                }
            });
        }
        
        private void handleTunnel(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.writeAndFlush(in.retainedDuplicate());
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (outboundChannel != null && outboundChannel.isActive()) outboundChannel.close();
            super.channelInactive(ctx);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error("SOCKS5 handler error", cause);
            ctx.close();
        }
    }
    
    /** SOCKS5 双向数据转发器 */
    static class Socks5Forwarder extends SimpleChannelInboundHandler<ByteBuf> {
        private final Channel inboundChannel;
        public Socks5Forwarder(Channel inbound) { this.inboundChannel = inbound; }
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            if (inboundChannel.isActive()) inboundChannel.writeAndFlush(buf.retainedDuplicate());
        }
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (inboundChannel.isActive()) inboundChannel.close();
        }
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error("SOCKS5 forwarder error", cause);
            ctx.close();
        }
    }
}
