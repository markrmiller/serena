package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.List;

/**
 * Ordered set of {@link ResourceReferenceProvider}s (refactor-feature-plan-V3.md §15). Each resource file is handled by
 * exactly one provider: the first, most-specific provider that {@link ResourceReferenceProvider#supports(Path) supports}
 * it. {@link ReflectionResourceProvider} is last and claims anything no structured provider recognizes, so a file is
 * never double-counted.
 */
final class ResourceProviderRegistry {

    private final List<ResourceReferenceProvider> providers;

    ResourceProviderRegistry() {
        this.providers = List.of(
                new ServiceLoaderResourceProvider(),
                new XmlResourceProvider(),
                new StructuredTextResourceProvider(),
                new ReflectionResourceProvider());
    }

    /** The single provider responsible for {@code file}, or {@code null} if none applies. */
    ResourceReferenceProvider providerFor(Path file) {
        for (ResourceReferenceProvider provider : providers) {
            if (provider.supports(file)) {
                return provider;
            }
        }
        return null;
    }
}
