package dev.vcore.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI vCoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("V-Core API")
                        .version("v1")
                        .description(
                                "Conflict-safe, multi-tenant delivery workspace API. Browser traffic is authenticated by the trusted Cloudflare BFF.")
                        .contact(new Contact().name("V-Core portfolio project")));
    }
}
