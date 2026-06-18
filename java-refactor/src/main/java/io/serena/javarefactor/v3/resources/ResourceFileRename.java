package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;

/**
 * A resource FILE that must be renamed when a Java type/package moves (refactor-feature-plan-V3.md §15.2). The canonical
 * case is a {@code META-INF/services/<interface-fqn>} ServiceLoader registration whose service-interface FQN is encoded
 * in its filename: when that interface type moves, the file is renamed so {@code ServiceLoader.load(NewSpi.class)} still
 * resolves it.
 *
 * @param from       the resource file's current absolute path
 * @param to         the resource file's new absolute path
 * @param providerId the provider that planned this rename
 * @param reason     a human-readable explanation surfaced as a warning
 */
public record ResourceFileRename(Path from, Path to, String providerId, String reason) {
}
