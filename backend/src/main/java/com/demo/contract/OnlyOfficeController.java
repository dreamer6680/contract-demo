package com.demo.contract;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/onlyoffice")
@CrossOrigin(origins = "*")
public class OnlyOfficeController {
  private static final Pattern DISPLAY_HASH = Pattern.compile("[a-f0-9]{16}");

  private final Path storage;
  private final Path templatesDir;
  private final Path onlyOfficeDir;
  private final Path displayDir;
  private final Path defaultTemplate = Paths.get("/app/templates/coal-sale-contract.docx");
  private final String documentServerUrl;
  private final String documentUrlBase;
  private final String callbackUrlBase;
  private final String internalDocumentServerUrl;
  private final String jwtSecret;
  private final PlaceholderProtectionService placeholderProtection = new PlaceholderProtectionService();
  private final Map<String, String> activeKeys = new ConcurrentHashMap<>();
  private final Map<String, AtomicLong> saveCounters = new ConcurrentHashMap<>();

  public OnlyOfficeController(
      @Value("${app.storage-dir:/app/storage}") String storageDir,
      @Value("${ONLYOFFICE_DOCUMENT_SERVER_URL:http://localhost:8081}") String documentServerUrl,
      @Value("${ONLYOFFICE_DOCUMENT_URL_BASE:http://host.docker.internal:8080}") String documentUrlBase,
      @Value("${ONLYOFFICE_CALLBACK_URL_BASE:http://backend:8080}") String callbackUrlBase,
      @Value("${ONLYOFFICE_INTERNAL_URL:http://onlyoffice}") String internalDocumentServerUrl,
      @Value("${ONLYOFFICE_JWT_SECRET:local-onlyoffice-dev-secret-change-me}") String jwtSecret) throws IOException {
    this.storage = Paths.get(storageDir).toAbsolutePath().normalize();
    this.templatesDir = this.storage.resolve("templates");
    this.onlyOfficeDir = this.storage.resolve("onlyoffice");
    this.displayDir = this.onlyOfficeDir.resolve("display");
    Files.createDirectories(this.templatesDir);
    Files.createDirectories(this.onlyOfficeDir);
    Files.createDirectories(this.displayDir);
    this.documentServerUrl = documentServerUrl.replaceAll("/+$", "");
    this.documentUrlBase = documentUrlBase.replaceAll("/+$", "");
    this.callbackUrlBase = callbackUrlBase.replaceAll("/+$", "");
    this.internalDocumentServerUrl = internalDocumentServerUrl.replaceAll("/+$", "");
    this.jwtSecret = jwtSecret;
  }

  /** Backward-compatible endpoint: opens the template with empty values. */
  @GetMapping("/config/{templateId}")
  public Map<String, Object> config(@PathVariable String templateId) throws IOException {
    return buildConfig(templateId, Map.of());
  }

  /**
   * Create an editing session. The DOCX served to ONLYOFFICE contains the current
   * business values inside content controls; the original token stays in the tag.
   */
  @PostMapping("/config/{templateId}")
  public Map<String, Object> configWithValues(@PathVariable String templateId,
      @RequestBody(required = false) Map<String, Object> body) throws IOException {
    return buildConfig(templateId, readValues(body));
  }

  /**
   * Preflight used by the editor page. Besides checking that Document Server is
   * reachable, it sends a signed CommandService request; error=6 means the
   * Document Server rejected our JWT, i.e. the two services have different
   * ONLYOFFICE_JWT_SECRET values.
   */
  @GetMapping("/ready")
  public Map<String, Object> ready() {
    try {
      RestClient.create().get()
          .uri(internalDocumentServerUrl + "/healthcheck")
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      return Map.of("ready", false, "code", "UNAVAILABLE",
          "message", "ONLYOFFICE Document Server 未启动或不可达");
    }
    Map<String, Object> command = Map.of("c", "info");
    try {
      String token = sign(command);
      Object response = RestClient.create().post()
          .uri(internalDocumentServerUrl + "/coauthoring/CommandService.ashx")
          .header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(command)
          .retrieve()
          .body(Object.class);
      if (response instanceof Map<?, ?> map) {
        Object error = map.get("error");
        if (error instanceof Number n && n.intValue() == 6) {
          return Map.of("ready", false, "code", "JWT_MISMATCH",
              "message", "backend 与 ONLYOFFICE 的 JWT 密钥不一致，请用相同的 ONLYOFFICE_JWT_SECRET 重建两个容器");
        }
      }
      return Map.of("ready", true);
    } catch (Exception e) {
      return Map.of("ready", false, "code", "COMMAND_FAILED",
          "message", e.getMessage() == null ? "ONLYOFFICE 命令服务不可用" : e.getMessage());
    }
  }

  private Map<String, Object> buildConfig(String templateId, Map<String, String> values) throws IOException {
    Path canonical = ensureCanonical(templateId);
    String hash = valuesHash(values);
    Path display = displayPath(templateId, hash);
    Files.createDirectories(display.getParent());
    placeholderProtection.createDisplayCopy(canonical, display, values);

    String key = safeKey(templateId.replaceAll("[^A-Za-z0-9_-]", "_") + "-"
        + Files.getLastModifiedTime(canonical).toMillis() + "-" + hash + "-"
        + Files.getLastModifiedTime(display).toMillis());
    activeKeys.put(templateId, key);
    saveCounters.computeIfAbsent(templateId, ignored -> new AtomicLong());

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("fileType", "docx");
    document.put("key", key);
    document.put("title", templateName(templateId) + ".docx");
    document.put("url", documentUrlBase + "/api/onlyoffice/document/" + templateId + "?v=" + hash);
    document.put("permissions", Map.of("edit", true, "download", true, "print", true));

    Map<String, Object> editorConfig = new LinkedHashMap<>();
    editorConfig.put("mode", "edit");
    editorConfig.put("lang", "zh-CN");
    editorConfig.put("callbackUrl", callbackUrlBase + "/api/onlyoffice/callback/" + templateId);
    editorConfig.put("user", Map.of("id", "dev-user", "name", "开发验证用户"));

    Map<String, Object> config = new LinkedHashMap<>();
    config.put("documentType", "word");
    config.put("document", document);
    config.put("editorConfig", editorConfig);
    config.put("height", "100%");
    config.put("width", "100%");
    config.put("token", sign(config));
    return Map.of("documentServerUrl", documentServerUrl, "config", config, "valuesHash", hash);
  }

  @GetMapping("/document/{templateId}")
  public ResponseEntity<FileSystemResource> document(@PathVariable String templateId,
      @RequestParam(value = "v", required = false) String version) throws IOException {
    Path path = version == null || version.isBlank()
        ? ensureCanonical(templateId)
        : displayPath(templateId, version);
    if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .body(new FileSystemResource(path));
  }

  @PostMapping("/callback/{templateId}")
  public Map<String, Object> callback(@PathVariable String templateId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody Map<String, Object> body) throws IOException {
    // Require a Document Server-signed callback token; never trust arbitrary public POSTs.
    String callbackToken = authorization != null && authorization.startsWith("Bearer ")
        ? authorization.substring(7) : (body.get("token") instanceof String token ? token : null);
    if (!validToken(callbackToken)) return Map.of("error", 1);
    Object statusValue = body.get("status");
    int status = statusValue instanceof Number n ? n.intValue() : -1;
    // ONLYOFFICE statuses 2 (ready for saving) and 6 (force-save result) carry a document URL.
    if (status == 2 || status == 6) {
      Object urlValue = body.get("url");
      if (!(urlValue instanceof String url) || url.isBlank()) return Map.of("error", 1);
      Path canonical = ensureCanonical(templateId);
      Path candidate = Files.createTempFile(onlyOfficeDir, "callback-", ".docx");
      Path restored = Files.createTempFile(onlyOfficeDir, "restored-", ".docx");
      try {
        // The callback URL is generated from the Document Server's public URL
        // (e.g. http://localhost:8081). Inside the backend container that host is
        // the backend itself, so fetch the same path through the internal service URL.
        byte[] bytes = RestClient.create().get().uri(internalCallbackUrl(url))
            .retrieve().body(byte[].class);
        if (bytes == null || bytes.length == 0) throw new IOException("保存回调没有返回文档");
        Files.write(candidate, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        // The editor has business values in content controls. Restore the tags to
        // placeholder tokens before validating and persisting the template.
        placeholderProtection.restorePlaceholders(candidate, restored);
        placeholderProtection.assertIntegrity(canonical, restored);
        move(restored, canonical);
        saveCounters.computeIfAbsent(templateId, ignored -> new AtomicLong()).incrementAndGet();
      } catch (Exception ex) {
        return Map.of("error", 1,
            "message", ex.getMessage() == null ? "保存失败" : ex.getMessage());
      } finally {
        Files.deleteIfExists(candidate);
        Files.deleteIfExists(restored);
      }
    }
    return Map.of("error", 0);
  }

  /**
   * Snapshot the current canonical token document as a new template version.
   * Forces ONLYOFFICE to flush first so the snapshot contains the latest edits.
   */
  @PostMapping("/versions/{templateId}")
  public Map<String, Object> saveVersion(@PathVariable String templateId,
      @RequestBody(required = false) Map<String, Object> body) throws IOException {
    // ONLYOFFICE's idle autosave only updates its editor cache. The canonical DOCX
    // (and therefore generation) is refreshed by the status=6 callback, which
    // forcesave triggers. Always force before snapshotting.
    boolean flushed = forceSave(templateId);
    Path canonical = ensureCanonical(templateId);
    placeholderProtection.assertIntegrity(resolveTemplate(templateId), canonical);

    String name = body != null && body.get("name") != null ? String.valueOf(body.get("name")).trim() : "";
    if (name.isBlank()) name = "合同模板版本";
    String id = UUID.randomUUID().toString();
    Path target = templatesDir.resolve(id + ".docx").normalize();
    if (!target.startsWith(templatesDir)) throw new IllegalArgumentException("非法模板路径");
    Files.copy(canonical, target, StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(templatesDir.resolve(id + ".name"), name);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("name", name);
    result.put("isDefault", false);
    result.put("flushed", flushed);
    result.put("message", flushed
        ? "模板版本已保存；编辑器中的业务值已还原为占位符。"
        : "模板版本已保存；未收到编辑器强制保存回执，如内容不是最新，请再点一次保存。");
    return result;
  }

  private boolean forceSave(String templateId) {
    String key = activeKeys.get(templateId);
    if (key == null || key.isBlank()) return false;
    AtomicLong counter = saveCounters.computeIfAbsent(templateId, ignored -> new AtomicLong());
    long before = counter.get();
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("c", "forcesave");
    command.put("key", key);
    try {
      String token = sign(command);
      RestClient.create().post()
          .uri(internalDocumentServerUrl + "/coauthoring/CommandService.ashx")
          .header("Authorization", "Bearer " + token)
          .contentType(MediaType.APPLICATION_JSON)
          .body(command)
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      return false;
    }
    long deadline = System.currentTimeMillis() + 8000;
    while (System.currentTimeMillis() < deadline) {
      if (counter.get() > before) return true;
      try {
        Thread.sleep(150);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /**
   * Returns the editable token-only document for a template. Older POC copies may
   * still contain protected content controls; migrate them back to plain tokens.
   */
  private Path ensureCanonical(String templateId) throws IOException {
    Path canonical = canonicalPath(templateId);
    Path source = resolveTemplate(templateId);
    if (!Files.isRegularFile(source)) throw new IOException("模板不存在：" + templateId);
    if (!Files.isRegularFile(canonical)) {
      Files.copy(source, canonical, StandardCopyOption.REPLACE_EXISTING);
      return canonical;
    }
    if (placeholderProtection.hasPlaceholderControls(canonical)) {
      Path restored = Files.createTempFile(onlyOfficeDir, "canonical-", ".docx");
      try {
        placeholderProtection.restorePlaceholders(canonical, restored);
        move(restored, canonical);
      } finally {
        Files.deleteIfExists(restored);
      }
    }
    // Never let a broken draft silently lose the template's placeholders.
    try {
      placeholderProtection.assertIntegrity(source, canonical);
    } catch (IOException integrity) {
      Files.copy(source, canonical, StandardCopyOption.REPLACE_EXISTING);
    }
    return canonical;
  }

  private Path canonicalPath(String id) {
    Path p = onlyOfficeDir.resolve(safeId(id) + ".docx").normalize();
    if (!p.startsWith(onlyOfficeDir)) throw new IllegalArgumentException("非法模板路径");
    return p;
  }

  private Path displayPath(String id, String hash) {
    if (hash == null || !DISPLAY_HASH.matcher(hash).matches()) {
      throw new IllegalArgumentException("非法编辑参数");
    }
    Path p = displayDir.resolve(safeId(id) + "-" + hash + ".docx").normalize();
    if (!p.startsWith(displayDir)) throw new IllegalArgumentException("非法模板路径");
    return p;
  }

  private void move(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Document Server returns its public download URL in the callback. The backend
   * must fetch it through the internal service address instead. Only URLs that
   * point at the configured Document Server (or its internal address) are allowed.
   */
  private String internalCallbackUrl(String url) throws IOException {
    if (url.startsWith(internalDocumentServerUrl + "/")) return url;
    if (url.startsWith(documentServerUrl + "/")) {
      return internalDocumentServerUrl + url.substring(documentServerUrl.length());
    }
    try {
      URI target = URI.create(url);
      URI external = URI.create(documentServerUrl);
      if (target.getHost() != null
          && external.getHost() != null
          && target.getHost().equalsIgnoreCase(external.getHost())
          && target.getPort() == external.getPort()
          && target.getRawPath() != null) {
        String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
        return internalDocumentServerUrl + target.getRawPath() + query;
      }
    } catch (Exception ignored) {
      // Fall through to the validation error below.
    }
    throw new IOException("非法回调文档地址：" + url);
  }

  private Map<String, String> readValues(Map<String, Object> body) {
    Map<String, String> values = new LinkedHashMap<>();
    Object raw = body == null ? null : body.get("data");
    if (raw instanceof Map<?, ?> map) {
      map.forEach((k, v) -> values.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
    }
    return values;
  }

  private String valuesHash(Map<String, String> values) throws IOException {
    StringBuilder canonical = new StringBuilder();
    new TreeMap<>(values).forEach((k, v) -> canonical.append(k).append('=').append(v).append('\n'));
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.substring(0, 16);
    } catch (Exception e) {
      throw new IOException("无法计算编辑参数摘要", e);
    }
  }

  private String templateName(String id) {
    if ("default".equals(id)) return "煤炭买卖合同";
    Path nameFile = templatesDir.resolve(safeId(id) + ".name");
    if (Files.isRegularFile(nameFile)) {
      try { return Files.readString(nameFile).trim(); } catch (IOException ignored) { /* Fallback. */ }
    }
    return id;
  }

  private String safeKey(String key) {
    return key.length() <= 120 ? key : key.substring(0, 120);
  }

  private Path resolveTemplate(String id) {
    if ("default".equals(id)) return defaultTemplate;
    String safe = safeId(id);
    if (!safe.equals(id)) throw new IllegalArgumentException("非法模板 ID");
    Path uploaded = templatesDir.resolve(id + ".docx").normalize();
    if (!uploaded.startsWith(templatesDir)) throw new IllegalArgumentException("非法模板路径");
    return uploaded;
  }

  private String safeId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("非法模板 ID");
    return id;
  }

  private boolean validToken(String token) {
    if (token == null || token.isBlank()) return false;
    try { JWT.require(Algorithm.HMAC256(jwtSecret)).build().verify(token); return true; }
    catch (Exception ignored) { return false; }
  }

  private String sign(Map<String, Object> payload) {
    return JWT.create().withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
        .withPayload(payload)
        .sign(Algorithm.HMAC256(jwtSecret));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleError(Exception e) {
    String message = e.getMessage() == null ? "编辑器服务异常" : e.getMessage();
    return ResponseEntity.status(500).body(Map.of("message", message));
  }
}
