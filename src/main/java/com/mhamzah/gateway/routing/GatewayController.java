package com.mhamzah.gateway.routing;

import com.mhamzah.gateway.config.GatewayProperties;
import com.mhamzah.gateway.engine.ExecutionResult;
import com.mhamzah.gateway.engine.InboundRequest;
import com.mhamzah.gateway.extension.GatewayResponse;
import com.mhamzah.gateway.logging.CorrelationId;
import com.mhamzah.gateway.extension.InboundFile;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catch-all endpoint under {@code gateway.api-base-path}; every request is routed by method + path. The body is
 * read by its content type:
 * <ul>
 *   <li>JSON (or anything not listed below): parsed as JSON into {@code $.request.body}.</li>
 *   <li>{@code multipart/form-data}: text fields into {@code $.request.body}, files into {@code $.request.files}.</li>
 *   <li>{@code application/x-www-form-urlencoded}: the form fields into {@code $.request.body}.</li>
 *   <li>A binary type (PDF, images, Office documents, zip, octet-stream, ...): the whole body as the file
 *       {@code $.request.files.body}, named by the {@code Content-Disposition} filename or {@code X-File-Name}.</li>
 * </ul>
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
    private static final Pattern DISPOSITION_FILENAME = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
            Pattern.CASE_INSENSITIVE);

    private final GatewayService service;
    private final String basePath;
    private final long maxRawBytes;

    public GatewayController(GatewayService service, GatewayProperties properties) {
        this.service = service;
        this.basePath = properties.apiBasePath();
        this.maxRawBytes = properties.files().maxSize().toBytes();
    }

    @RequestMapping("${gateway.api-base-path:/api}/**")
    public ResponseEntity<String> handle(HttpServletRequest request, @RequestBody(required = false) String body) {
        return route(request, body, null, Map.of());
    }

    /** File uploads: text parts are the body fields, parts with a file name are files. */
    @RequestMapping(path = "${gateway.api-base-path:/api}/**", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> handleMultipart(HttpServletRequest request) throws IOException, ServletException {
        ObjectNode form = JsonNodeFactory.instance.objectNode();
        Map<String, InboundFile> files = new LinkedHashMap<>();
        for (Part part : request.getParts()) {
            if (part.getSubmittedFileName() != null) {
                // the first file of a field wins (as for repeated query parameters)
                files.putIfAbsent(part.getName(), new InboundFile(part.getName(), part.getSubmittedFileName(),
                        part.getContentType(), part.getInputStream().readAllBytes()));
            } else if (!form.has(part.getName())) {
                form.put(part.getName(), new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        files.values().forEach(f -> log.info("upload field={} filename={} contentType={} size={}", f.field(),
                f.filename(), f.contentType(), f.size()));
        return route(request, null, form, files);
    }

    /** HTML-style forms: the fields (not the query string's) are the body. */
    @RequestMapping(path = "${gateway.api-base-path:/api}/**", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> handleForm(HttpServletRequest request) {
        Map<String, String> query = parseQuery(request.getQueryString());
        ObjectNode form = JsonNodeFactory.instance.objectNode();
        request.getParameterMap().forEach((name, values) -> {
            // the servlet merges query and form parameters; a name only in the query string is not a form field
            if (values.length > 0 && !(query.containsKey(name) && values.length == 1 && values[0].equals(query.get(name)))) {
                form.put(name, values[values.length - 1]);
            }
        });
        return route(request, null, form, Map.of());
    }

    /** A raw binary body (a PDF, an image, ...) is one file, field {@code body}. */
    @RequestMapping(path = "${gateway.api-base-path:/api}/**", consumes = {MediaType.APPLICATION_OCTET_STREAM_VALUE,
            MediaType.APPLICATION_PDF_VALUE, "image/*", "audio/*", "video/*", "application/zip", "application/gzip",
            "text/csv", "application/msword", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
    public ResponseEntity<String> handleBinary(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxRawBytes + 1));
        if (bytes.length > maxRawBytes) {
            throw new MaxUploadSizeExceededException(maxRawBytes);
        }
        String name = request.getHeader("X-File-Name");
        String disposition = request.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition != null) {
            Matcher m = DISPOSITION_FILENAME.matcher(disposition);
            if (m.find()) {
                name = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            }
        }
        InboundFile file = new InboundFile("body", name, request.getContentType(), bytes);
        log.info("upload field=body filename={} contentType={} size={}", file.filename(), file.contentType(), file.size());
        return route(request, null, JsonNodeFactory.instance.objectNode(), Map.of("body", file));
    }

    private ResponseEntity<String> route(HttpServletRequest request, String body, ObjectNode form,
            Map<String, InboundFile> files) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        Map<String, String> query = parseQuery(request.getQueryString());
        String path = request.getRequestURI().substring(request.getContextPath().length() + basePath.length());
        InboundRequest inbound = new InboundRequest(HttpMethod.valueOf(request.getMethod()),
                path.isEmpty() ? "/" : path, headers, query, body, form, files);

        ExecutionResult result = service.handle(inbound, CorrelationId.of(request));
        return toResponseEntity(result.response());
    }

    /** First value per name. Parsed from the raw query string so a form-encoded body is never consumed. */
    public static Map<String, String> parseQuery(String queryString) {
        Map<String, String> query = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return query;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.putIfAbsent(name, value);
        }
        return query;
    }

    private static ResponseEntity<String> toResponseEntity(GatewayResponse response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach(headers::set);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(response.status())
                .headers(headers)
                .body(response.body() == null ? null : response.body().toString());
    }
}
