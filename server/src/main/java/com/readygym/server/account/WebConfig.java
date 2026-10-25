package com.readygym.server.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;

    /**
     * Where the full ExerciseGymGifsDB mirror lives on this host (the repo's 366 MiB gifdb). When
     * set, GIFs are served from there; the small curated subset packaged in the jar
     * (src/main/resources/static/gifs) stays as the fallback so a bare jar still demonstrates
     * every equipment-guide entry. Blank = package only.
     */
    @Value("${readygym.gifs-dir:}")
    private String gifsDir;

    /**
     * Optional directory of downloadable artifacts (the debug APK, release notes), served at
     * /download/&lt;file&gt;. Blank disables the route. Files are served by exact name from this
     * one directory — it is not a browsing tree.
     */
    @Value("${readygym.downloads-dir:}")
    private String downloadsDir;

    public WebConfig(AuthInterceptor auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/register", "/api/admin/**", "/api/health", "/api/feedback", "/api/asr/**");
    }

    /**
     * Equipment-guide demonstration GIFs. Unauthenticated on purpose: they carry no user data, and
     * the app's exercise guide needs them before login. Immutable content + far-future caching,
     * because a file's path is its content address in this dataset.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        List<String> locations = new ArrayList<>();
        if (!gifsDir.isBlank()) {
            String dir = Path.of(gifsDir).toAbsolutePath().toUri().toString();
            locations.add(dir.endsWith("/") ? dir : dir + "/");
        }
        locations.add("classpath:/static/gifs/");
        registry.addResourceHandler("/gifs/**")
            .addResourceLocations(locations.toArray(new String[0]))
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());

        // Installer downloads: version your filenames (ReadyGym-0.4.0.apk) so the cache is safe.
        if (!downloadsDir.isBlank()) {
            String dir = Path.of(downloadsDir).toAbsolutePath().toUri().toString();
            registry.addResourceHandler("/download/**")
                .addResourceLocations(dir.endsWith("/") ? dir : dir + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
        }
    }
}
