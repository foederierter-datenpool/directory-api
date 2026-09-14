package de.civicdata.directory.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "Directory API"), servers = @Server(url = "/"))
public class DirectoryApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DirectoryApiApplication.class, args);
    }
}
