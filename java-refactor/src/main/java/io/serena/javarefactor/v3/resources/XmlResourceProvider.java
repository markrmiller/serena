package io.serena.javarefactor.v3.resources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds exact dotted class/package tokens in XML resources (Spring/JPA/Jackson/general config)
 * (refactor-feature-plan-V3.md §15). XML config typically carries fully-qualified class names in attribute values and
 * element text, so structurally-exact tokens are {@link ResourceConfidence#HIGH}.
 *
 * <p>A Spring {@code <bean class="com.acme.Foo"/>} element is recognized specially: the {@code class} attribute value is
 * classified as {@link ResourceReferenceKind#SPRING_BEAN_CLASS} so the propagating safe-delete planner can remove the
 * whole bean definition (refactor-feature-plan-V3.md §7.3 step 8) when the deleted type is the bean's sole role, and
 * {@link #removableBeanElementSpan} computes that exact element span — but only when removing it is unambiguous.
 */
final class XmlResourceProvider implements ResourceReferenceProvider {

    /** Matches a {@code class="..."} (or {@code class='...'}) attribute on a {@code <bean ...>} start tag. */
    private static final Pattern BEAN_CLASS_ATTR =
            Pattern.compile("<bean\\b[^>]*?\\bclass\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.DOTALL);

    @Override
    public String id() {
        return "xml";
    }

    @Override
    public boolean supports(Path file) {
        return file.getFileName() != null
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    @Override
    public List<ResourceReference> findReferences(Path file, String content, ResourceQuery query) {
        List<ResourceReference> refs = new ArrayList<>();
        // The character offsets of every <bean class="..."> attribute value, so a dotted token sitting inside one is
        // classified as a Spring bean class rather than a generic exact-class token.
        List<int[]> beanClassValueSpans = beanClassValueSpans(content);
        for (ResourceSupport.Token token : ResourceSupport.dottedTokens(content)) {
            if (ResourceSupport.matches(token.text(), query)) {
                ResourceReferenceKind kind = kindFor(token, query, beanClassValueSpans);
                refs.add(new ResourceReference(file, token.start(), token.end(), token.text(),
                        kind, ResourceConfidence.HIGH, query.target(), id()));
            }
        }
        return refs;
    }

    @Override
    public ResourceEditPlan planEdits(Path file, String content, ResourceRenameRequest request) {
        return ResourceEditPlan.ofEdits(ResourceSupport.planTokenEdits(file, content, request, id()));
    }

    private static ResourceReferenceKind kindFor(ResourceSupport.Token token, ResourceQuery query,
            List<int[]> beanClassValueSpans) {
        if (!query.isPackage() && token.text().equals(query.target()) && within(token, beanClassValueSpans)) {
            return ResourceReferenceKind.SPRING_BEAN_CLASS;
        }
        if (query.isPackage() && !token.text().equals(query.target())) {
            return ResourceReferenceKind.PACKAGE_PREFIX;
        }
        return ResourceReferenceKind.EXACT_CLASS_NAME;
    }

    private static boolean within(ResourceSupport.Token token, List<int[]> spans) {
        for (int[] span : spans) {
            if (token.start() >= span[0] && token.end() <= span[1]) {
                return true;
            }
        }
        return false;
    }

    /** The {@code [start,end)} offsets of every {@code <bean ... class="VALUE" ...>} attribute VALUE in {@code content}. */
    private static List<int[]> beanClassValueSpans(String content) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = BEAN_CLASS_ATTR.matcher(content);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(1), matcher.end(1)});
        }
        return spans;
    }

    /**
     * The {@code [start,end)} offsets of the whole {@code <bean ...>...</bean>} (or self-closing {@code <bean .../>})
     * element whose {@code class} attribute value contains {@code classTokenOffset}, or {@code null} when removing the
     * element is NOT unambiguous (refactor-feature-plan-V3.md §7.3 step 8 / §7.5).
     *
     * <p>Removal is unambiguous only when the bean's sole role is instantiating the deleted type: the element is a plain
     * {@code <bean>} (not a parent/abstract bean, not a factory producer) and its {@code id}/{@code name}, if any, is not
     * wired into another bean via a {@code ref}/{@code bean}/{@code parent}/{@code factory-bean}/{@code depends-on}
     * attribute or a nested {@code <ref bean="..."/>}. A bean that other beans depend on is left for human review.
     */
    static int[] removableBeanElementSpan(String content, int classTokenOffset) {
        int tagStart = content.lastIndexOf("<bean", classTokenOffset);
        if (tagStart < 0) {
            return null;
        }
        int startTagEnd = content.indexOf('>', tagStart);
        if (startTagEnd < 0 || classTokenOffset > startTagEnd) {
            return null; // the token is not inside this <bean ...> start tag
        }
        String startTag = content.substring(tagStart, startTagEnd + 1);
        // A factory/parent/abstract bean's role is more than instantiation; never auto-remove those.
        if (attribute(startTag, "factory-method") != null
                || attribute(startTag, "factory-bean") != null
                || "true".equalsIgnoreCase(attribute(startTag, "abstract"))
                || attribute(startTag, "parent") != null) {
            return null;
        }
        int elementEnd;
        boolean selfClosing = startTag.endsWith("/>");
        if (selfClosing) {
            elementEnd = startTagEnd + 1;
        } else {
            int closeTag = content.indexOf("</bean>", startTagEnd);
            if (closeTag < 0) {
                return null;
            }
            elementEnd = closeTag + "</bean>".length();
        }
        // If this bean exposes an id/name that any OTHER part of the document references, removing it would dangle that
        // wiring — that is ambiguous, so the planner must warn rather than delete.
        if (isReferencedElsewhere(content, startTag, tagStart, elementEnd)) {
            return null;
        }
        // Absorb the run of horizontal whitespace before the element and the single trailing newline so the removal does
        // not leave a blank line where the bean was.
        int removeStart = tagStart;
        while (removeStart > 0) {
            char ch = content.charAt(removeStart - 1);
            if (ch == ' ' || ch == '\t') {
                removeStart--;
            } else {
                break;
            }
        }
        int removeEnd = elementEnd;
        if (removeEnd < content.length() && content.charAt(removeEnd) == '\r') {
            removeEnd++;
        }
        if (removeEnd < content.length() && content.charAt(removeEnd) == '\n') {
            removeEnd++;
        }
        return new int[] {removeStart, removeEnd};
    }

    private static boolean isReferencedElsewhere(String content, String startTag, int elementStart, int elementEnd) {
        for (String idAttr : new String[] {"id", "name"}) {
            String value = attribute(startTag, idAttr);
            if (value == null) {
                continue;
            }
            // A bean's name attribute may list several aliases (comma/space/semicolon-separated); any referenced alias
            // makes removal ambiguous.
            for (String alias : value.split("[,;\\s]+")) {
                if (alias.isEmpty()) {
                    continue;
                }
                if (referencesId(content, alias, elementStart, elementEnd)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code id} appears as a bean reference ({@code ref=}/{@code bean=}/{@code parent=}/{@code factory-bean=}/
     * {@code depends-on=}) anywhere in {@code content} OUTSIDE the {@code [elementStart,elementEnd)} span of the bean
     * that declares it.
     */
    private static boolean referencesId(String content, String id, int elementStart, int elementEnd) {
        String quoted = Pattern.quote(id);
        Pattern reference = Pattern.compile(
                "\\b(?:ref|bean|parent|factory-bean|depends-on|local)\\s*=\\s*[\"']" + quoted + "[\"']");
        Matcher matcher = reference.matcher(content);
        while (matcher.find()) {
            if (matcher.start() < elementStart || matcher.start() >= elementEnd) {
                return true;
            }
        }
        return false;
    }

    /** The value of {@code name} attribute in {@code startTag}, or {@code null} if absent. */
    private static String attribute(String startTag, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(startTag);
        return matcher.find() ? matcher.group(1) : null;
    }
}
