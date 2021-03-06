package com.blade.mvc.http;

import com.blade.kit.StringKit;
import com.blade.mvc.WebContext;
import com.blade.mvc.multipart.FileItem;
import com.blade.mvc.route.Route;
import com.blade.server.netty.HttpConst;
import com.blade.server.netty.SessionHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.util.CharsetUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.*;

/**
 * Http Request Impl
 *
 * @author biezhi
 * 2017/5/31
 */
@Slf4j
public class HttpRequest implements Request {

    private static final HttpDataFactory HTTP_DATA_FACTORY = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
    private static final SessionHandler  SESSION_HANDLER   = WebContext.sessionManager() != null ? new SessionHandler(WebContext.blade()) : null;

    static {
        DiskFileUpload.deleteOnExitTemporaryFile = true;
        DiskFileUpload.baseDirectory = null;
        DiskAttribute.deleteOnExitTemporaryFile = true;
        DiskAttribute.baseDirectory = null;
    }

    private ByteBuf body = Unpooled.copiedBuffer("", CharsetUtil.UTF_8);
    private String  remoteAddress;
    private String  uri;
    private String  url;
    private String  protocol;
    private String  method;
    private boolean keepAlive;

    private Map<String, String>       headers    = null;
    private Map<String, Object>       attributes = null;
    private Map<String, List<String>> parameters = new HashMap<>();
    private Map<String, String>       pathParams = null;
    private Map<String, Cookie>       cookies    = new HashMap<>();
    private Map<String, FileItem>     fileItems  = new HashMap<>();

    private void init(FullHttpRequest fullHttpRequest) {
        // headers
        HttpHeaders httpHeaders = fullHttpRequest.headers();
        if (!httpHeaders.isEmpty()) {
            this.headers = new HashMap<>(httpHeaders.size());
            Iterator<Map.Entry<String, String>> entryIterator = httpHeaders.iteratorAsString();
            while (entryIterator.hasNext()) {
                Map.Entry<String, String> entry = entryIterator.next();
                headers.put(entry.getKey(), entry.getValue());
            }
        } else {
            this.headers = new HashMap<>();
        }

        // body content
        this.body = fullHttpRequest.content().copy();

        // request query parameters
        Map<String, List<String>> parameters = new QueryStringDecoder(fullHttpRequest.uri(), CharsetUtil.UTF_8).parameters();
        if (null != parameters) {
            this.parameters = new HashMap<>();
            this.parameters.putAll(parameters);
        }

        if (HttpConst.METHOD_POST.equals(this.method)) {
            HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(HTTP_DATA_FACTORY, fullHttpRequest);
            decoder.getBodyHttpDatas().forEach(this::parseData);
        }

        // cookies
        String cookie = header(HttpConst.COOKIE_STRING);
        cookie = cookie.length() > 0 ? cookie : header(HttpConst.COOKIE_STRING.toLowerCase());
        if (StringKit.isNotEmpty(cookie)) {
            ServerCookieDecoder.LAX.decode(cookie).forEach(this::parseCookie);
        }
    }

    private void parseData(InterfaceHttpData data) {
        try {
            switch (data.getHttpDataType()) {
                case Attribute:
                    Attribute attribute = (Attribute) data;
                    String name = attribute.getName();
                    String value = attribute.getValue();

                    List<String> values;
                    if (this.parameters.containsKey(name)) {
                        values = this.parameters.get(name);
                        values.add(value);
                    } else {
                        values = new ArrayList<>();
                        values.add(value);
                        this.parameters.put(name, values);
                    }

                    break;
                case FileUpload:
                    FileUpload fileUpload = (FileUpload) data;
                    parseFileUpload(fileUpload);
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            log.error("Parse request parameter error", e);
        } finally {
            data.release();
        }
    }

    private void parseFileUpload(FileUpload fileUpload) throws IOException {
        if (fileUpload.isCompleted()) {
            String contentType = StringKit.mimeType(fileUpload.getFilename());
            if (null == contentType) {
                contentType = URLConnection.guessContentTypeFromName(fileUpload.getFilename());
            }
            if (fileUpload.isInMemory()) {
                FileItem fileItem = new FileItem(fileUpload.getName(), fileUpload.getFilename(),
                        contentType, fileUpload.length());

                ByteBuf byteBuf = fileUpload.getByteBuf();
                fileItem.setData(ByteBufUtil.getBytes(byteBuf));
                fileItems.put(fileItem.getName(), fileItem);
            } else {
                FileItem fileItem = new FileItem(fileUpload.getName(), fileUpload.getFilename(),
                        contentType, fileUpload.length());
                byte[] bytes = Files.readAllBytes(fileUpload.getFile().toPath());
                fileItem.setData(bytes);
                fileItems.put(fileItem.getName(), fileItem);
            }
        }
    }

    /**
     * parse netty cookie to {@link Cookie}.
     *
     * @param nettyCookie netty raw cookie instance
     */
    private void parseCookie(io.netty.handler.codec.http.cookie.Cookie nettyCookie) {
        Cookie cookie = new Cookie();
        cookie.name(nettyCookie.name());
        cookie.value(nettyCookie.value());
        cookie.httpOnly(nettyCookie.isHttpOnly());
        cookie.path(nettyCookie.path());
        cookie.domain(nettyCookie.domain());
        cookie.maxAge(nettyCookie.maxAge());
        this.cookies.put(cookie.name(), cookie);
    }

    @Override
    public Request initPathParams(@NonNull Route route) {
        if (null != route.getPathParams())
            this.pathParams = route.getPathParams();
        return this;
    }

    @Override
    public String host() {
        return this.header("Host");
    }

    @Override
    public String remoteAddress() {
        return this.remoteAddress;
    }

    @Override
    public String uri() {
        return this.uri;
    }

    @Override
    public String url() {
        return this.url;
    }

    @Override
    public String protocol() {
        return this.protocol;
    }

    @Override
    public Map<String, String> pathParams() {
        return this.pathParams;
    }

    @Override
    public String queryString() {
        if (null != this.url && this.url.contains("?")) {
            return this.url.substring(this.url.indexOf("?") + 1);
        }
        return "";
    }

    @Override
    public Map<String, List<String>> parameters() {
        return this.parameters;
    }

    @Override
    public Set<String> parameterNames() {
        return this.parameters.keySet();
    }

    @Override
    public List<String> parameterValues(String paramName) {
        return this.parameters.get(paramName);
    }

    @Override
    public String method() {
        return this.method;
    }

    @Override
    public HttpMethod httpMethod() {
        return HttpMethod.valueOf(method());
    }

    @Override
    public Session session() {
        return SESSION_HANDLER.createSession(this);
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public boolean isIE() {
        String ua = userAgent();
        return ua.contains("MSIE") || ua.contains("TRIDENT");
    }

    @Override
    public Map<String, Cookie> cookies() {
        return this.cookies;
    }

    @Override
    public Cookie cookieRaw(@NonNull String name) {
        return this.cookies().get(name);
    }

    @Override
    public Request cookie(@NonNull Cookie cookie) {
        this.cookies().put(cookie.name(), cookie);
        return this;
    }

    @Override
    public Map<String, String> headers() {
        return this.headers;
    }

    @Override
    public boolean keepAlive() {
        return this.keepAlive;
    }

    @Override
    public Map<String, Object> attributes() {
        if (null == this.attributes) {
            this.attributes = new HashMap<>(4);
        }
        return this.attributes;
    }

    @Override
    public Map<String, FileItem> fileItems() {
        return fileItems;
    }

    @Override
    public ByteBuf body() {
        return this.body;
    }

    public HttpRequest() {
    }

    public HttpRequest(Request request) {
        this.pathParams = request.pathParams();
        this.cookies = request.cookies();
        this.attributes = request.attributes();
        this.body = request.body();
        this.fileItems = request.fileItems();
        this.headers = request.headers();
        this.keepAlive = request.keepAlive();
        this.method = request.method();
        this.url = request.url();

        if (null != this.url && this.url.length() > 0) {
            int pathEndPos = this.url.indexOf('?');
            this.uri = pathEndPos < 0 ? this.url : this.url.substring(0, pathEndPos);
        }

        this.parameters = request.parameters();
        this.protocol = request.protocol();
    }

    public static HttpRequest build(FullHttpRequest fullHttpRequest, String remoteAddress) {
        HttpRequest httpRequest = new HttpRequest();
        httpRequest.keepAlive = HttpUtil.isKeepAlive(fullHttpRequest);
        httpRequest.remoteAddress = remoteAddress;
        httpRequest.url = fullHttpRequest.uri();
        int pathEndPos = httpRequest.url.indexOf('?');
        httpRequest.uri = pathEndPos < 0 ? httpRequest.url : httpRequest.url.substring(0, pathEndPos);
        httpRequest.protocol = fullHttpRequest.protocolVersion().text();
        httpRequest.method = fullHttpRequest.method().name();
        httpRequest.init(fullHttpRequest);
        return httpRequest;
    }

}