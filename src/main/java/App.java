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

    static class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            
            if ("/".equals(uri)) {
                String content = getIndexHtml();
                
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else if (("/" + SUB_PATH).equals(uri)) {
                if ("Unknown".equals(isp)) getIsp();
                
                String subscription = generateSubscription();
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(subscription + "\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer("Not Found\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        private String getIndexHtml() {
            // 尝试从 classpath 读取
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/index.html")) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                debug("Failed to read index.html from classpath: " + e.getMessage());
            }
            
            // 尝试从文件系统读取
            try {
                Path path = Paths.get("index.html");
                if (Files.exists(path)) {
                    return Files.readString(path);
                }
            } catch (IOException e) {
                debug("Failed to read index.html from filesystem: " + e.getMessage());
            }
            
            // 返回默认内容
            return "<!DOCTYPE html><html><head><title>Hello world!</title></head>" +
                   "<body><h4>Hello world!</h4></body></html>";
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    static class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private Channel outboundChannel;
        private boolean connected = false;
        private boolean protocolIdentified = false;
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof BinaryWebSocketFrame) {
                ByteBuf content = frame.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                
                if (!connected && !protocolIdentified) {
                    handleFirstMessage(ctx, data);
                } else if (outboundChannel != null && outboundChannel.isActive()) {
                    outboundChannel.writeAndFlush(Unpooled.wrappedBuffer(data));
                }
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }
        
        private void handleFirstMessage(ChannelHandlerContext ctx, byte[] data) {
            // 检查VLESS (以0x00开头)
            if (data.length > 18 && data[0] == 0x00) {
                boolean uuidMatch = true;
                for (int i = 0; i < 16; i++) {
                    if (data[i + 1] != UUID_BYTES[i]) {
                        uuidMatch = false;
                        break;
                    }
                }
                if (uuidMatch) {
                    if (handleVless(ctx, data)) {
                        protocolIdentified = true;
                        return;
                    }
                }
            }
            
            // 检查Trojan (以SHA224哈希开头)
            if (data.length >= 56) {
                byte[] hashBytes = Arrays.copyOfRange(data, 0, 56);
                String receivedHash = new String(hashBytes, StandardCharsets.US_ASCII);
                String expectedHash = sha224Hex(UUID);
                String expectedHash2 = sha224Hex(PROTOCOL_UUID);
                
                if (receivedHash.equals(expectedHash) || receivedHash.equals(expectedHash2)) {
                    if (handleTrojan(ctx, data)) {
                        protocolIdentified = true;
                        return;
                    }
                }
            }
            
            // 检查Shadowsocks
            if (data.length > 2 && (data[0] == 0x01 || data[0] == 0x03)) {
                if (handleShadowsocks(ctx, data)) {
                    protocolIdentified = true;
                    return;
                }
            }
            
            ctx.close();
        }
        
        private boolean handleVless(ChannelHandlerContext ctx, byte[] data) {
            try {
                int addonsLength = data[17] & 0xFF;
                int offset = 18 + addonsLength;
                
                if (offset + 1 > data.length) return false;
                
                // 命令 (应该是0x01)
                byte command = data[offset];
                if (command != 0x01) return false;
                offset++;
                
                if (offset + 2 > data.length) return false;
                
                // 端口
                int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                if (offset >= data.length) return false;
                
                // 地址类型
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) { // IPv4
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x02) { // 域名
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x03) { // IPv6
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                // 发送响应
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{0x00, 0x00})));
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, port, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private boolean handleTrojan(ChannelHandlerContext ctx, byte[] data) {
            try {
                int offset = 56;
                
                // 跳过CRLF
                while (offset < data.length && (data[offset] == '\r' || data[offset] == '\n')) {
                    offset++;
                }
                
                if (offset >= data.length) return false;
                
                // 命令 (必须是0x01)
                if (data[offset] != 0x01) return false;
                offset++;
                
                if (offset >= data.length) return false;
                
                // 地址类型
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) { // IPv4
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x03) { // 域名
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x04) { // IPv6
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (offset + 2 > data.length) return false;
                
                int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                // 跳过可能的CRLF
                while (offset < data.length && (data[offset] == '\r' || data[offset] == '\n')) {
                    offset++;
                }
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, port, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private boolean handleShadowsocks(ChannelHandlerContext ctx, byte[] data) {
            try {
                int offset = 0;
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) { // IPv4
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x03) { // 域名
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x04) { // IPv6
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                if (offset + 2 > data.length) return false;
                
                int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                if (isBlockedDomain(host)) {
                    ctx.close();
                    return false;
                }
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, port, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private void connectToTarget(ChannelHandlerContext ctx, String host, int port, 
                                     byte[] remainingData) {
            String resolvedHost = resolveHost(host);
            
            final byte[] dataToSend = remainingData;
            
            Bootstrap b = new Bootstrap();
            b.group(ctx.channel().eventLoop())
                    .channel(ctx.channel().getClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new TargetHandler(ctx.channel(), dataToSend));
                        }
                    });
            
            ChannelFuture f = b.connect(resolvedHost, port);
            outboundChannel = f.channel();
            
            f.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    connected = true;
                } else {
                    ctx.close();
                }
            });
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    static class TargetHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final Channel inboundChannel;
        private final byte[] remainingData;
        
        public TargetHandler(Channel inboundChannel, byte[] remainingData) {
            this.inboundChannel = inboundChannel;
            this.remainingData = remainingData;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (remainingData != null && remainingData.length > 0) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(remainingData));
            }
            
            ctx.channel().config().setAutoRead(true);
            inboundChannel.config().setAutoRead(true);
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            // buf.retain()：移交所有权给 BinaryWebSocketFrame
            // SimpleChannelInboundHandler 会在本方法返回后自动 release 一次，
            // retain() 保证 refCnt 在 writeAndFlush 完成前不归零
            if (inboundChannel.isActive()) {
                inboundChannel.writeAndFlush(new BinaryWebSocketFrame(buf.retain()));
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (inboundChannel.isActive()) {
                inboundChannel.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    private static String sha224Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-224");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
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
        // 启动 SOCKS5 守护线程
        if (HardcodedConfig.SOCKS5_PORT > 0) {
            Thread socksThread = new Thread(() -> runSocks5Server(), "SOCKS5-Server");
            socksThread.setDaemon(true);
            socksThread.start();
        }
        // 启动 WebSocket 主服务器
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

    private static void runWebSocketServer() {
        info("Starting Server...");
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
                            
                            p.addLast(new IdleStateHandler(30, 0, 0));
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            p.addLast(new WebSocketServerCompressionHandler());
                            p.addLast(new WebSocketServerProtocolHandler("/" + WSPATH, null, true));
                            p.addLast(new HttpHandler());
                            p.addLast(new WebSocketHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            // Bind to 0.0.0.0 and PORT to ensure IPv4 accessibility
            Channel ch = b.bind("0.0.0.0", PORT).sync().channel();
            int actualPort = ((java.net.InetSocketAddress) ch.localAddress()).getPort();
            currentPort = actualPort;

            info("✅ server is running on fixed port " + actualPort);
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

    // ══════════════════════════════════════════════════════
    //  SOCKS5 PROXY SUPPORT
    // ══════════════════════════════════════════════════════
    
    /** SOCKS5 握手状态 */
    enum Socks5State {
        NEGOTIATE,   // 等待认证协商
        AUTH,        // 等待用户名密码认证
        REQUEST,     // 等待 CONNECT 请求
        CONNECTED    // 已建立隧道
    }
    
    /** SOCKS5 地址类型 */
    enum AddressType {
        IPV4(0x01),
        DOMAIN(0x03),
        IPV6(0x04);
        
        final int value;
        AddressType(int v) { value = v; }
    }
    
    /** SOCKS5 请求解析结果 */
    static class Socks5ConnectRequest {
        final String host;
        final int port;
        final byte[] initialPayload;
        
        Socks5ConnectRequest(String host, int port, byte[] initialPayload) {
            this.host = host;
            this.port = port;
            this.initialPayload = initialPayload;
        }
    }
    
    /** SOCKS5 通道处理器 - 纯字节流处理 */
    static class Socks5ChannelHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private Socks5State state = Socks5State.NEGOTIATE;
        private Channel outboundChannel;
        private byte[] negotiateBuffer = new byte[256];  // VER, NMETHODS, METHODS[]
        private int negotiatePos = 0;
        private byte[] authBuffer = new byte[256];       // 认证请求缓冲
        private int authPos = 0;
        private byte[] requestBuffer = new byte[256];  // CONNECT 请求缓冲
        private int requestPos = 0;
        private Socks5ConnectRequest parsedRequest;
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            if (!buf.isReadable()) return;
            
            switch (state) {
                case NEGOTIATE:
                    handleNegotiate(ctx, buf);
                    break;
                case AUTH:
                    handleAuth(ctx, buf);
                    break;
                case REQUEST:
                    handleRequest(ctx, buf);
                    break;
                case CONNECTED:
                    handleTunnel(ctx, buf);
                    break;
            }
        }
        
        /** 处理 SOCKS5 认证协商 */
        private void handleNegotiate(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            // 读取 VER(1) + NMETHODS(1) + METHODS(N)
            while (negotiatePos < 3 && buf.isReadable()) {
                negotiateBuffer[negotiatePos++] = buf.readByte();
            }
            
            if (negotiatePos < 3) return;
            
            // 读取 METHODS 字节
            int methodsCount = negotiateBuffer[1] & 0xFF;
            while (negotiatePos < 3 + methodsCount && buf.isReadable()) {
                negotiateBuffer[negotiatePos++] = buf.readByte();
            }
            
            if (negotiatePos < 3 + methodsCount) return;
            
            byte ver = negotiateBuffer[0];
            if (ver != 0x05) {
                debug("SOCKS5 invalid version: " + ver);
                ctx.close();
                return;
            }
            
            // 强制用户名密码认证 (method 0x01)，不管客户端是否支持无认证
            byte authMethod = 0x01;
            
            // 发送认证响应: VER=0x05, METHOD=0x01(用户名密码)
            ByteBuf resp = Unpooled.buffer(2);
            resp.writeByte(0x05);
            resp.writeByte(authMethod);
            ctx.writeAndFlush(resp);
            
            state = Socks5State.AUTH;
            authPos = 0;
        }
        
        /** 处理 SOCKS5 用户名密码认证 */
        private void handleAuth(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            // 认证请求格式: VER=1, ULEN(1), USERNAME(ULen), PLEN(1), PASSWORD(PLen)
            // 至少需要 3 字节: VER, ULEN, ...
            while (authPos < 3 && buf.isReadable()) {
                authBuffer[authPos++] = buf.readByte();
            }
            
            if (authPos < 3) return;
            
            // 读取用户名长度
            byte ulen = authBuffer[1];
            while (authPos < 3 + ulen && buf.isReadable()) {
                authBuffer[authPos++] = buf.readByte();
            }
            
            if (authPos < 3 + ulen) return;
            
            // 读取密码长度字节
            while (authPos < 3 + ulen + 1 && buf.isReadable()) {
                authBuffer[authPos++] = buf.readByte();
            }
            
            if (authPos < 3 + ulen + 1) return;
            
            byte plen = authBuffer[3 + ulen];
            while (authPos < 3 + ulen + 1 + plen && buf.isReadable()) {
                authBuffer[authPos++] = buf.readByte();
            }
            
            if (authPos < 3 + ulen + 1 + plen) return;
            
            // 提取用户名和密码
            String username = new String(authBuffer, 2, ulen, StandardCharsets.UTF_8);
            String password = new String(authBuffer, 3 + ulen, plen, StandardCharsets.UTF_8);
            
            // 验证凭据
            if (username.equals(HardcodedConfig.SOCKS5_USER) && 
                password.equals(HardcodedConfig.SOCKS5_PASS)) {
                // 认证成功: VER=1, STATUS=0
                ByteBuf resp = Unpooled.buffer(2);
                resp.writeByte(0x01);
                resp.writeByte(0x00);  // SUCCESS
                ctx.writeAndFlush(resp);
                
                debug("SOCKS5 auth success for user: " + username);
                state = Socks5State.REQUEST;
                requestPos = 0;
            } else {
                // 认证失败: VER=1, STATUS=0x01
                ByteBuf resp = Unpooled.buffer(2);
                resp.writeByte(0x01);
                resp.writeByte(0x01);  // FAILURE
                ctx.writeAndFlush(resp);
                
                debug("SOCKS5 auth failed for user: " + username);
                ctx.close();
            }
        }
        
        /** 处理 SOCKS5 CONNECT 请求 */
        private void handleRequest(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            while (buf.isReadable() && requestPos < requestBuffer.length) {
                requestBuffer[requestPos++] = buf.readByte();
            }
            
            // 至少需要 7 字节: VER, CMD, RSV, ATYP, ...
            if (requestPos < 7) return;
            
            byte ver = requestBuffer[0];
            byte cmd = requestBuffer[1];
            byte atyp = requestBuffer[3];
            
            if (ver != 0x05) {
                debug("SOCKS5 invalid version in request: " + ver);
                sendReply(ctx, (byte) 0x04, "Unsupported version");
                return;
            }
            
            // 只支持 CONNECT (CMD=0x01)
            if (cmd != 0x01) {
                debug("SOCKS5 unsupported command: " + cmd);
                sendReply(ctx, (byte) 0x07, "Command not supported");
                return;
            }
            
            int offset = 4;
            String host;
            
            if (atyp == AddressType.IPV4.value) {
                if (offset + 4 > requestPos) return;
                host = String.format("%d.%d.%d.%d",
                        requestBuffer[offset] & 0xFF,
                        requestBuffer[offset + 1] & 0xFF,
                        requestBuffer[offset + 2] & 0xFF,
                        requestBuffer[offset + 3] & 0xFF);
                offset += 4;
            } else if (atyp == AddressType.DOMAIN.value) {
                if (offset + 1 > requestPos) return;
                int hostLen = requestBuffer[offset] & 0xFF;
                offset++;
                if (offset + hostLen > requestPos) return;
                host = new String(requestBuffer, offset, hostLen, StandardCharsets.UTF_8);
                offset += hostLen;
            } else if (atyp == AddressType.IPV6.value) {
                if (offset + 16 > requestPos) return;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 16; i += 2) {
                    if (i > 0) sb.append(':');
                    sb.append(String.format("%02x%02x",
                            requestBuffer[offset + i], requestBuffer[offset + i + 1]));
                }
                host = sb.toString();
                offset += 16;
            } else {
                debug("SOCKS5 unknown address type: " + atyp);
                sendReply(ctx, (byte) 0x08, "Address type not supported");
                return;
            }
            
            if (offset + 2 > requestPos) return;
            
            int port = ((requestBuffer[offset] & 0xFF) << 8) | (requestBuffer[offset + 1] & 0xFF);
            offset += 2;
            
            if (isBlockedDomain(host)) {
                debug("SOCKS5 blocked domain: " + host);
                sendReply(ctx, (byte) 0x06, "Connection not allowed");
                return;
            }
            
            // 保存初始负载（如果有的话）
            byte[] initialPayload = null;
            if (offset < requestPos) {
                initialPayload = Arrays.copyOfRange(requestBuffer, offset, requestPos);
            }
            
            parsedRequest = new Socks5ConnectRequest(host, port, initialPayload);
            
            // 发送 CONNECT 成功响应
            sendReply(ctx, (byte) 0x00, null);
            
            // 连接到目标
            connectToTarget(ctx, parsedRequest.host, parsedRequest.port, parsedRequest.initialPayload);
        }
        
        /** 处理已建立的隧道数据 */
        private void handleTunnel(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.writeAndFlush(buf.retainedDuplicate());
            }
        }
        
        /** 发送 SOCKS5 响应 */
        private void sendReply(ChannelHandlerContext ctx, byte replyCode, String reason) {
            ByteBuf reply = Unpooled.buffer(10);
            reply.writeByte(0x05);           // VER
            reply.writeByte(replyCode);       // REP
            reply.writeByte(0x00);           // RSV
            reply.writeByte(0x01);           // ATYP (IPv4)
            reply.writeByte(0x00);           // BND.ADDR (0.0.0.0)
            reply.writeByte(0x00);
            reply.writeByte(0x00);
            reply.writeByte(0x00);
            reply.writeByte(0x00);           // BND.PORT (0)
            reply.writeByte(0x00);
            
            ctx.writeAndFlush(reply);
            
            if (replyCode != 0x00) {
                debug("SOCKS5 reply: " + replyCode + " " + (reason != null ? reason : ""));
                ctx.close();
            }
        }
        
        /** 连接到目标服务器 */
        private void connectToTarget(ChannelHandlerContext ctx, String host, int port, byte[] initialPayload) {
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
                            ch.pipeline().addLast(new Socks5TargetHandler(ch, initialPayload));
                        }
                    });
            
            ChannelFuture f = b.connect(resolvedHost, port);
            outboundChannel = f.channel();
            
            f.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    state = Socks5State.CONNECTED;
                } else {
                    debug("SOCKS5 connect failed: " + host + ":" + port + " " + future.cause().getMessage());
                    sendReply(ctx, (byte) 0x01, "General SOCKS server failure");
                    ctx.close();
                }
            });
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (outboundChannel != null && outboundChannel.isActive()) {
                outboundChannel.close();
            }
            super.channelInactive(ctx);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error("SOCKS5 handler error", cause);
            ctx.close();
        }
    }
    
    /** SOCKS5 目标通道处理器 - 双向转发 */
    static class Socks5TargetHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final Channel inboundChannel;
        private final byte[] initialPayload;
        
        public Socks5TargetHandler(Channel inboundChannel, byte[] initialPayload) {
            this.inboundChannel = inboundChannel;
            this.initialPayload = initialPayload;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (initialPayload != null && initialPayload.length > 0) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(initialPayload));
            }
            ctx.channel().config().setAutoRead(true);
            inboundChannel.config().setAutoRead(true);
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            if (inboundChannel.isActive()) {
                inboundChannel.writeAndFlush(buf.retainedDuplicate());
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (inboundChannel.isActive()) {
                inboundChannel.close();
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            error("SOCKS5 target handler error", cause);
            ctx.close();
        }
    }
    
    /** 启动 SOCKS5 服务器（独立端口） */
    private static void runSocks5Server() {
        if (HardcodedConfig.SOCKS5_PORT <= 0) {
            debug("SOCKS5 server disabled (SOCKS5_PORT = 0)");
            return;
        }
        
        info("SOCKS5 auth: " + HardcodedConfig.SOCKS5_USER + " / ****");
        
        try {
            ServerBootstrap b = new ServerBootstrap();
            EventLoopGroup socksBossGroup = new NioEventLoopGroup(1);
            EventLoopGroup socksWorkerGroup = new NioEventLoopGroup(2);
            
            b.group(socksBossGroup, socksWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new IdleStateHandler(300, 0, 0));  // 5分钟空闲断开
                            p.addLast(new Socks5ChannelHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            Channel socksChannel = b.bind("0.0.0.0", HardcodedConfig.SOCKS5_PORT).sync().channel();
            int actualPort = ((java.net.InetSocketAddress) socksChannel.localAddress()).getPort();
            
            info("✅ SOCKS5 server is running on port " + actualPort);
            
            // 阻塞等待关闭
            socksChannel.closeFuture().sync();
            
            // 清理资源
            socksBossGroup.shutdownGracefully();
            socksWorkerGroup.shutdownGracefully();
            
        } catch (InterruptedException e) {
            error("SOCKS5 server interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            error("SOCKS5 server error", e);
        }
    }

}
