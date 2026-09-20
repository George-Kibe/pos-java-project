package com.pos.notification.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Two template engines, one per body format.
 *
 * <p>Declared explicitly rather than layering a second resolver onto the auto-configured engine.
 * With one engine, both resolvers see the same logical template name and the first one to claim it
 * wins, which makes "why did the text body come out as HTML" a resolution-order puzzle. Two engines
 * make the choice explicit at the call site.
 */
@Configuration
public class ThymeleafEmailConfig {

    @Bean
    public TemplateEngine htmlEmailTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver("templates/email/", ".html", TemplateMode.HTML));
        return engine;
    }

    @Bean
    public TemplateEngine textEmailTemplateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver("templates/email/text/", ".txt", TemplateMode.TEXT));
        return engine;
    }

    private static ClassLoaderTemplateResolver resolver(
            String prefix, String suffix, TemplateMode mode) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(prefix);
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Templates ship inside the jar and never change at runtime.
        resolver.setCacheable(true);
        return resolver;
    }
}
