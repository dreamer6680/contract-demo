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
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/onlyoffice")
@CrossOrigin(origins = "*")
public class OnlyOfficeController {
  private final Path storage;
  private final Path templatesDir;
  private final Path defaultTemplate = Paths.get("/app/templates/coal-sale-contract.docx");
  private final String documentServerUrl;
  private final String documentUrlBase;
  private final String callbackUrlBase;
  private final String jwtSecret;
  private final PlaceholderProtectionService placeholderProtection = new PlaceholderProtectionService();

  public OnlyOfficeController(
      @Value("${app.storage-dir:/app/storage}") String storageDir,
      @Value("${ONLYOFFICE_DOCUMENT_SERVER_URL:http://localhost:8081}") String documentServerUrl,
      @Value("${ONLYOFFICE_DOCUMENT_URL_BASE:http://host.docker.internal:8080}") String documentUrlBase,
      @Value("${ONLYOFFICE_CALLBACK_URL_BASE:http://backend:8080}") String callbackUrlBase,
      @Value("${ONLYOFFICE_JWT_SECRET:local-onlyoffice-dev-secret-change-me}") String jwtSecret) throws IOException {
    this.storage = Paths.get(storageDir).toAbsolutePath().normalize();
    this.templatesDir = this.storage.resolve("templates");
    Files.createDirectories(this.storage.resolve("onlyoffice"));
    this.documentServerUrl = documentServerUrl.replaceAll("/+$", "");
    this.documentUrlBase = documentUrlBase.replaceAll("/+$", "");
    this.callbackUrlBase = callbackUrlBase.replaceAll("/+$", "");
    this.jwtSecret = jwtSecret;
  }

  @GetMapping("/config/{templateId}")
  public Map<String, Object> config(@PathVariable String templateId) throws IOException {
    Path source = ensureProtectedDocument(templateId);
    if (!Files.isRegularFile(source)) throw new IllegalArgumentException("模板不存在：" + templateId);
    String key = templateId.replaceAll("[^A-Za-z0-9_-]", "_") + "-" + Files.getLastModifiedTime(source).toMillis();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("fileType", "docx");
    document.put("key", key);
    document.put("title", "合同模板 - " + templateId + ".docx");
    document.put("url", documentUrlBase + "/api/onlyoffice/document/" + templateId);
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
    return Map.of("documentServerUrl", documentServerUrl, "config", config);
  }

  @GetMapping("/document/{templateId}")
  public ResponseEntity<FileSystemResource> document(@PathVariable String templateId) throws IOException {
    Path path = ensureProtectedDocument(templateId);
    if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        .body(new FileSystemResource(path));
  }

  @PostMapping("/callback/{templateId}")
  public Map<String, Object> callback(@PathVariable String templateId, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> body) throws IOException {
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
      Path target = storage.resolve("onlyoffice").resolve(safeId(templateId) + ".docx").normalize();
      if (!target.startsWith(storage.resolve("onlyoffice"))) return Map.of("error", 1);
      byte[] bytes = RestClient.create().get().uri(url).retrieve().body(byte[].class);
      if (bytes == null || bytes.length == 0) return Map.of("error", 1);
      Path candidate = Files.createTempFile(storage.resolve("onlyoffice"), "callback-", ".docx");
      try {
        Files.write(candidate, bytes, StandardOpenOption.TRUNCATE_EXISTING);
        // Compare against the immutable source template, not the previously saved copy.
        placeholderProtection.assertIntegrity(resolveTemplate(templateId), candidate);
        if (placeholderProtection.hasProtectedControls(candidate)) {
          Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
          // Reapply locks if the editor removed content controls but left token text intact.
          placeholderProtection.createProtectedCopy(candidate, target);
          Files.deleteIfExists(candidate);
        }
      } catch (IOException ex) {
        Files.deleteIfExists(candidate);
        return Map.of("error", 1, "message", ex.getMessage());
      }
    }
    return Map.of("error", 0);
  }

  private Path ensureProtectedDocument(String id) throws IOException {
    Path baseline = resolveTemplate(id);
    if (!Files.isRegularFile(baseline)) return baseline;
    Path saved = storage.resolve("onlyoffice").resolve(safeId(id) + ".docx").normalize();
    if (!saved.startsWith(storage.resolve("onlyoffice"))) throw new IllegalArgumentException("非法模板路径");
    if (!Files.isRegularFile(saved)) {
      placeholderProtection.createProtectedCopy(baseline, saved);
    } else if (!placeholderProtection.hasProtectedControls(saved)) {
      // Migrate POC saves created before placeholder locking was added.
      placeholderProtection.createProtectedCopy(saved, saved);
    }
    return saved;
  }
  private Path editableDocument(String id) {
    Path saved = storage.resolve("onlyoffice").resolve(safeId(id) + ".docx").normalize();
    if (!saved.startsWith(storage.resolve("onlyoffice"))) throw new IllegalArgumentException("非法模板路径");
    if (Files.isRegularFile(saved)) return saved;
    return resolveTemplate(id);
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
}
