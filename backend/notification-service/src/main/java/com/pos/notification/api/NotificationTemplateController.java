package com.pos.notification.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.BrandLogo;
import com.pos.notification.service.EmailMessage;
import com.pos.notification.service.EmailTemplateRenderer;
import com.pos.notification.service.SmtpEmailSender;
import com.pos.notification.service.TemplateSamples;
import com.pos.notification.service.TemplateTextService;
import com.pos.notification.service.TemplateTexts;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The wording of the emails the system sends - subject, opening and closing - with a preview
 * rendered from sample values. The layout and what an email carries (a code, a link, a receipt)
 * stay in code. {@code settings:manage}, which only the administrator holds.
 */
@RestController
@RequestMapping("/api/v1/notification-templates")
@RequiredArgsConstructor
@Tag(name = "Email wording")
public class NotificationTemplateController {

    private final TemplateTextService texts;
    private final EmailTemplateRenderer renderer;
    private final TemplateSamples samples;

    public record WordingRequest(
            @NotBlank @Size(max = 200) String subject,
            @Size(max = 1000) String intro,
            @Size(max = 1000) String closing) {}

    public record WordingResponse(
            NotificationType type, String subject, String intro, String closing, boolean changed) {}

    public record PreviewResponse(String subject, String html, String text) {}

    @GetMapping
    @PreAuthorize("hasAuthority('settings:manage')")
    @Operation(summary = "The wording of every kind of email, and whether it has been changed")
    public List<WordingResponse> list() {
        return texts.all().stream()
                .map(
                        wording ->
                                new WordingResponse(
                                        wording.type(),
                                        wording.texts().subject(),
                                        wording.texts().intro(),
                                        wording.texts().closing(),
                                        wording.changed()))
                .toList();
    }

    @PutMapping("/{type}")
    @PreAuthorize("hasAuthority('settings:manage')")
    @Operation(summary = "Change an email's wording; the next one sent uses it")
    public WordingResponse save(
            @PathVariable NotificationType type, @Valid @RequestBody WordingRequest request) {
        TemplateTexts saved =
                texts.save(type, request.subject(), request.intro(), request.closing());
        return new WordingResponse(type, saved.subject(), saved.intro(), saved.closing(), true);
    }

    @DeleteMapping("/{type}")
    @PreAuthorize("hasAuthority('settings:manage')")
    @Operation(summary = "Put an email back to the wording that ships")
    public WordingResponse reset(@PathVariable NotificationType type) {
        TemplateTexts shipped = texts.reset(type);
        return new WordingResponse(
                type, shipped.subject(), shipped.intro(), shipped.closing(), false);
    }

    @PostMapping("/{type}/preview")
    @PreAuthorize("hasAuthority('settings:manage')")
    @Operation(summary = "Render an email with this wording and sample values; nothing is saved")
    public ResponseEntity<PreviewResponse> preview(
            @PathVariable NotificationType type, @Valid @RequestBody WordingRequest request) {
        EmailMessage message =
                renderer.render(
                        type,
                        "someone@example.com",
                        "Ada Lovelace",
                        samples.modelFor(type),
                        TemplateTextService.checked(
                                request.subject(), request.intro(), request.closing()));
        return ResponseEntity.ok(
                new PreviewResponse(
                        message.subject(),
                        // The sent message carries the logo as an attachment; a page needs it
                        // inline.
                        message.html()
                                .replace(
                                        "cid:" + SmtpEmailSender.LOGO_CONTENT_ID,
                                        BrandLogo.dataUri()),
                        message.text()));
    }
}
