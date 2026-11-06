package com.readygym.server.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    /** Only simple, versioned .apk names are served; no slashes, so no traversal is possible. */
    private static final Pattern DOWNLOAD_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.apk");

    /** Filesystem roots and OS system directories must never become a resource location. */
    private static final Set<String> SYSTEM_DIRS = Set.of(
        "c:/windows", "c:/program files", "c:/program files (x86)", "c:/programdata",
        "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/boot", "/dev",
        "/proc", "/sys", "/system", "/library", "/applications"
    );

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
        String gifs = safeDir(gifsDir);
        if (gifs != null) locations.add(gifs);
        locations.add("classpath:/static/gifs/");
        registry.addResourceHandler("/gifs/**")
            .addResourceLocations(locations.toArray(new String[0]))
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());

        // Installer downloads: version your filenames (ReadyGym-0.4.0.apk) so the cache is safe.
        String downloads = safeDir(downloadsDir);
        if (downloads != null) {
            registry.addResourceHandler("/download/**")
                .addResourceLocations(downloads)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .resourceChain(false)   // no caching resolver: serving behaviour stays unchanged
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Keep Spring's built-in traversal check (super) and reject anything that
                        // is not a bare .apk filename. Returning null makes Spring answer 404.
                        return DOWNLOAD_NAME.matcher(resourcePath).matches()
                            ? super.getResource(resourcePath, location) : null;
                    }
                });
        }
    }

    /**
     * Normalizes an operator-supplied directory and refuses blanks, filesystem roots and OS system
     * directories. Returns a resource-location URI with a trailing slash, or null to leave the
     * route on its safe default (packaged GIFs / no download route) instead of failing startup.
     */
    private static String safeDir(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Path path;
        try {
            path = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("Ignoring invalid resource directory: {}", raw);
            return null;
        }
        Path root = path.getRoot();
        if (root == null || path.equals(root) || path.getNameCount() == 0) {
            log.warn("Ignoring filesystem root as resource directory: {}", path);
            return null;
        }
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String dir : SYSTEM_DIRS) {
            if (normalized.equals(dir) || normalized.startsWith(dir + "/")) {
                log.warn("Ignoring system directory as resource directory: {}", path);
                return null;
            }
        }
        String uri = path.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
