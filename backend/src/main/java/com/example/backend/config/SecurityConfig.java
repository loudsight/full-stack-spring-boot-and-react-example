package com.example.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.FileUrlResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.Md4PasswordEncoder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    RouterFunction<ServerResponse> resourceRouter(
            @Value("${webapp.static-files-location:./resources/static/}") String location
    ) throws MalformedURLException {
        return RouterFunctions.resources("/**", new FileUrlResource(location));
    }

    @Bean
    String[] unsecuredPaths(
            @Value("${application.static-files.paths}") String[] publicPaths,
            @Value("${application.static-files.prefixes}") List<String> resourcePrefixes
    ) {
        var publicPathPatterns = new ArrayList<>();
        var allResourcePrefixes = new ArrayList<>(resourcePrefixes);
        allResourcePrefixes.add("");

        for (String resourcePrefix : allResourcePrefixes) {
            for (String path : publicPaths) {
                String sitePath = "/" + resourcePrefix + path;
                sitePath = sitePath.replace("//", "/");
                publicPathPatterns.add(sitePath);
            }
        }

        return publicPathPatterns.toArray(new String[0]);
    }

    @Bean
    public SecurityWebFilterChain configure(ServerHttpSecurity http,
                                            ServerSecurityContextRepository securityContextRepository,
                                            String... unsecuredPaths
    ) {
        return http
                .csrf().disable()
                .cors().disable()
                .securityContextRepository(securityContextRepository)
                .authorizeExchange()
                .pathMatchers(unsecuredPaths).permitAll()
                .pathMatchers(HttpMethod.OPTIONS).permitAll()
                .pathMatchers("/**")
                .authenticated()
                .and()
                //.exceptionHandling().authenticationEntryPoint(new CustomAuthenticationEntryPoint())
                .oauth2Login(withDefaults())
                .oauth2Client(withDefaults())
                .httpBasic().disable()
                .formLogin().disable()
                .addFilterAt((exchange, chain) -> {
                    ServerHttpRequest request = exchange.getRequest();
                    String host /*= request.getHeaders().getFirst("X-Forwarded-Host");
                    logger.info("X-Forwarded-Host = " + host)*/;
//                    if (host == null) {
                    host = request.getHeaders().getHost().getHostName();
//                    }
                    if (logger.isInfoEnabled()) {
                        logger.info("Host: " + host);
                    }
                    String requestPath = request.getPath().value();

                    if (logger.isInfoEnabled()) {
                        logger.info("Path: = " + requestPath + " (" + ("/" + host + "/").equals(requestPath) + ")");
                    }

                    if (("/" + host + "/").equals(requestPath) || getClass().getResource("/static/" + requestPath) != null) {
                        if (requestPath.endsWith("/")) {
                            requestPath = requestPath.replace("/" + host, "") + "index.html";
                        }
                        ServerHttpResponse response = exchange.getResponse();
                        response.getHeaders().setLocation(URI.create(request.getPath().value()));
                        exchange = exchange.mutate().request(
                                        request.mutate().path(requestPath).build()
                                ).response(response)
                                .build();
                    }
                    return chain.filter(exchange);

                }, SecurityWebFiltersOrder.LAST)
                .logout().disable()
                .build();
    }

    //    @Bean
    public Customizer<ServerHttpSecurity.OAuth2LoginSpec> oauth2LoginCustomizer(
            ReactiveAuthenticationManager authenticationManager
    ) {
        return new Customizer<ServerHttpSecurity.OAuth2LoginSpec>() {
            @Override
            public void customize(ServerHttpSecurity.OAuth2LoginSpec oAuth2LoginSpec) {
                oAuth2LoginSpec.authenticationManager(authenticationManager);
            }
        };
    }


    @Bean
    public ServerSecurityContextRepository serverSecurityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public MapReactiveUserDetailsService userDetailsService() {
        UserDetails user = User
                .withUsername("user")
                .password(new Md4PasswordEncoder().encode("password"))
                .roles("USER")
                .build();
        return new MapReactiveUserDetailsService(user);
    }

    //    @Bean
    public WebClient webClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth =
                new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        return WebClient.builder()
                .filter(oauth)
                .build();
    }

    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ServerOAuth2AuthorizedClientRepository authorizedClientRepository) {

        ReactiveOAuth2AuthorizedClientProvider authorizedClientProvider =
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .build();

        DefaultReactiveOAuth2AuthorizedClientManager authorizedClientManager =
                new DefaultReactiveOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientRepository);

        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }
}
