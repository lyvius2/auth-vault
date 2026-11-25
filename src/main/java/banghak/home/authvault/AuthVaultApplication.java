package banghak.home.authvault;

import banghak.home.authvault.tool.VaultTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthVaultApplication.class, args);
    }

    @Bean
    ToolCallbackProvider vaultToolProvider(VaultTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
