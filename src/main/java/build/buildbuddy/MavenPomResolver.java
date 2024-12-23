package build.buildbuddy;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MavenPomResolver {

    private static final String NAMESPACE_4_0_0 = "http://maven.apache.org/POM/4.0.0";
    private static final Set<String> IMPLICITS = Set.of("groupId", "artifactId", "version", "packaging");
    private static final Pattern PROPERTY = Pattern.compile("(\\$\\{([\\w.]+)})");

    private final MavenRepository repository;
    private final MavenVersionNegotiator negotiator;
    private final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    public MavenPomResolver(MavenRepository repository, MavenVersionNegotiator negotiator) {
        this.repository = repository;
        this.negotiator = negotiator;
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<MavenDependency> dependencies(String groupId,
                                              String artifactId,
                                              String version,
                                              MavenDependencyScope scope) throws IOException {
        Map<DependencyCoordinate, UnresolvedPom> unresolved = new HashMap<>();
        Map<DependencyCoordinate, ResolvedPom> resolved = new HashMap<>();
        List<DependencyEntry> dependencies = new ArrayList<>();
        Map<DependencyKey, DependencyResolution> resolutions = new HashMap<>();
        ContextualPom initial = new ContextualPom(resolveOrCached(groupId, artifactId, version, resolved, unresolved),
                true,
                scope,
                Set.of());
        Queue<DependencyKey> queue = new ArrayDeque<>(traverse(resolved,
                unresolved,
                resolutions,
                initial.pom().managedDependencies(),
                dependencies,
                0,
                initial));
        System.out.println("Dependencies: " + dependencies);
        while (!queue.isEmpty()) { // TODO: avoid cycles? maybe already within dependency resolution?
            DependencyKey current = queue.remove();
            DependencyResolution resolution = resolutions.get(current);
            String negotiated = negotiator.resolve(current.groupId(),
                    current.artifactId(),
                    current.type(),
                    current.classifier(),
                    resolution.versions());
            if (negotiated.equals(resolution.version()) && resolution.scopes().stream().allMatch(resolution.scope()::implies)) {
                continue;
            }
            System.out.println("Reconsider: " + current.groupId() + ":" + current.artifactId() + ":" + negotiated);
            int offset = (int) dependencies.stream()
                    .takeWhile(entry -> !entry.key().equals(current))
                    .count();
            int remaining = 1; // TODO: short cut with no actual change after negotiation? (consider scope)
            do {
                DependencyEntry entry = dependencies.remove(offset);
                DependencyResolution transitive = resolutions.get(entry.key());
                int index = (int) dependencies.stream()
                        .limit(offset)
                        .filter(value -> value.key().equals(entry.key()))
                        .count();
                transitive.versions().remove(index);
                transitive.scopes().remove(index);
                if (transitive.versions().isEmpty()) {
                    resolutions.remove(entry.key()); // TODO: does this require special handling?
                } else if (!transitive.scopes().contains(entry.scope()) || !transitive.versions().contains(entry.version())) {
                    queue.add(entry.key());
                }
                remaining += entry.children() - 1;
            } while (remaining > 0);
            queue.addAll(traverse(resolved,
                    unresolved,
                    resolutions,
                    initial.pom().managedDependencies(),
                    dependencies,
                    offset,
                    new ContextualPom(resolveOrCached(current.groupId(),
                            current.artifactId(),
                            negotiated,
                            resolved,
                            unresolved), false, resolution.scope(), resolution.exclusions())));
            System.out.println("Dependencies: " + dependencies);
        }
        return dependencies.stream().map(DependencyEntry::key).distinct().map(key -> {
            DependencyResolution resolution = resolutions.get(key);
            return new MavenDependency(key.groupId(),
                    key.artifactId(),
                    resolution.version(),
                    key.type(),
                    key.classifier(),
                    resolution.scope(),
                    null,
                    false);
        }).toList();
    }

    private SequencedSet<DependencyKey> traverse(Map<DependencyCoordinate, ResolvedPom> resolved,
                                                 Map<DependencyCoordinate, UnresolvedPom> unresolved,
                                                 Map<DependencyKey, DependencyResolution> resolutions,
                                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                                 List<DependencyEntry> dependencies,
                                                 int index,
                                                 ContextualPom current) throws IOException {
        SequencedSet<DependencyKey> conflicting = new LinkedHashSet<>();
        Queue<ContextualPom> queue = new ArrayDeque<>();
        do {
            for (Map.Entry<DependencyKey, DependencyValue> entry : current.pom().dependencies().entrySet()) {
                if (current.exclusions().contains(new DependencyName(
                        entry.getKey().groupId(),
                        entry.getKey().artifactId()))) {
                    continue;
                }
                DependencyValue override = managedDependencies.get(entry.getKey()), value;
                if (current.root()) {
                    value = entry.getValue().with(override);
                } else {
                    value = override == null ? entry.getValue() : override.with(entry.getValue());
                    value = value.with(current.pom().managedDependencies().get(entry.getKey()));
                }
                if (!current.root() && Boolean.parseBoolean(value.optional())) {
                    continue;
                }
                MavenDependencyScope mergedScope = current.scope() == null
                        ? toScope(value.scope())
                        : current.scope().merge(toScope(value.scope()));
                if (mergedScope == null) {
                    continue;
                }
                DependencyResolution previous = resolutions.get(entry.getKey());
                if (previous == null) {
                    Set<DependencyName> exclusions = current.exclusions();
                    if (value.exclusions() != null) {
                        exclusions = new HashSet<>(exclusions);
                        exclusions.addAll(value.exclusions());
                    }
                    String version = negotiator.resolve(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            entry.getKey().type(),
                            entry.getKey().classifier(),
                            value.version());
                    resolutions.put(entry.getKey(), new DependencyResolution(
                            version,
                            new ArrayList<>(List.of(value.version())),
                            mergedScope,
                            new ArrayList<>(List.of(mergedScope)),
                            exclusions));
                    ResolvedPom pom = resolveOrCached(entry.getKey().groupId(),
                            entry.getKey().artifactId(),
                            version,
                            resolved,
                            unresolved);
                    queue.add(new ContextualPom(pom, false, mergedScope, exclusions));
                    dependencies.add(index++, new DependencyEntry(entry.getKey(),
                            pom.dependencies().size(),
                            value.version(),
                            mergedScope));
                } else {
                    if (!previous.versions().contains(value.version())
                            && !previous.version().equals(value.version())
                            || mergedScope.implies(previous.scope())) {
                        conflicting.add(entry.getKey());
                        previous.versions().add(value.version());
                        previous.scopes().add(mergedScope);
                    }
                    dependencies.add(index++, new DependencyEntry(entry.getKey(),
                            0,
                            value.version(),
                            mergedScope));
                }
            }
        } while ((current = queue.poll()) != null);
        return conflicting;
    }

    private UnresolvedPom assemble(InputStream inputStream,
                                   Set<DependencyCoordinate> children,
                                   Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException,
            SAXException,
            ParserConfigurationException {
        Document document;
        try (inputStream) {
            document = factory.newDocumentBuilder().parse(inputStream);
        }
        return switch (document.getDocumentElement().getNamespaceURI()) {
            case NAMESPACE_4_0_0 -> {
                DependencyCoordinate parent = toChildren400(document.getDocumentElement(), "parent")
                        .findFirst()
                        .map(node -> new DependencyCoordinate(
                                toTextChild400(node, "groupId").orElseThrow(missing("parent.groupId")),
                                toTextChild400(node, "artifactId").orElseThrow(missing("parent.artifactId")),
                                toTextChild400(node, "version").orElseThrow(missing("parent.version"))))
                        .orElse(null);
                Map<String, String> properties = new HashMap<>();
                Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
                SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
                if (parent != null) {
                    if (!children.add(new DependencyCoordinate(parent.groupId(), parent.artifactId(), parent.version()))) {
                        throw new IllegalStateException("Circular dependency to "
                                + parent.groupId() + ":" + parent.artifactId() + ":" + parent.version());
                    }
                    UnresolvedPom resolution = assembleOrCached(parent.groupId(),
                            parent.artifactId(),
                            parent.version(),
                            children,
                            unresolved);
                    properties.putAll(resolution.properties());
                    IMPLICITS.forEach(property -> {
                        String value = resolution.properties().get(property);
                        if (value != null) {
                            properties.put("parent." + property, value);
                            properties.put("project.parent." + property, value);
                        }
                    });
                    managedDependencies.putAll(resolution.managedDependencies());
                    dependencies.putAll(resolution.dependencies());
                }
                IMPLICITS.forEach(property -> toChildren400(document.getDocumentElement(), property)
                        .findFirst()
                        .ifPresent(node -> {
                            properties.put(property, node.getTextContent());
                            properties.put("project." + property, node.getTextContent());
                        }));
                toChildren400(document.getDocumentElement(), "properties")
                        .limit(1)
                        .flatMap(MavenPomResolver::toChildren)
                        .filter(node -> node.getNodeType() == Node.ELEMENT_NODE)
                        .forEach(node -> properties.put(node.getLocalName(), node.getTextContent()));
                toChildren400(document.getDocumentElement(), "dependencyManagement")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependencies"))
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(MavenPomResolver::toDependency400)
                        .forEach(entry -> managedDependencies.put(entry.getKey(), entry.getValue()));
                toChildren400(document.getDocumentElement(), "dependencies")
                        .limit(1)
                        .flatMap(node -> toChildren400(node, "dependency"))
                        .map(MavenPomResolver::toDependency400)
                        .forEach(entry -> dependencies.putLast(entry.getKey(), entry.getValue()));
                yield new UnresolvedPom(properties, managedDependencies, dependencies);
            }
            case null, default -> throw new IllegalArgumentException(
                    "Unknown namespace: " + document.getDocumentElement().getNamespaceURI());
        };
    }

    private UnresolvedPom assembleOrCached(String groupId,
                                           String artifactId,
                                           String version,
                                           Set<DependencyCoordinate> children,
                                           Map<DependencyCoordinate, UnresolvedPom> poms) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        UnresolvedPom pom = poms.get(coordinates);
        if (pom == null) {
            try {
                pom = assemble(repository.fetch(groupId,
                        artifactId,
                        version,
                        "pom",
                        null,
                        null).toInputStream(), children, poms);
            } catch (RuntimeException | SAXException | ParserConfigurationException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            poms.put(coordinates, pom);
        }
        return pom;
    }

    private ResolvedPom resolve(UnresolvedPom pom, Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        Map<DependencyKey, DependencyValue> managedDependencies = new HashMap<>();
        SequencedMap<DependencyKey, DependencyValue> dependencies = new LinkedHashMap<>();
        for (Map.Entry<DependencyKey, DependencyValue> entry : pom.managedDependencies().entrySet()) {
            DependencyKey key = entry.getKey().resolve(pom.properties());
            DependencyValue value = entry.getValue().resolve(pom.properties());
            if (Objects.equals("import", value.scope())) {
                UnresolvedPom imported = assembleOrCached(key.groupId(),
                        key.artifactId(),
                        value.version(),
                        new HashSet<>(),
                        unresolved);
                imported.managedDependencies().forEach((importKey, importValue) -> {
                    DependencyValue resolved = importValue.resolve(imported.properties());
                    if (!Objects.equals("import", resolved.scope())) {
                        managedDependencies.putIfAbsent(importKey.resolve(imported.properties()), resolved);
                    }
                });
            } else {
                managedDependencies.put(key, value);
            }
        }
        pom.dependencies().forEach((key, value) -> dependencies.put(
                key.resolve(pom.properties()),
                value.resolve(pom.properties())));
        return new ResolvedPom(managedDependencies, dependencies);
    }

    private ResolvedPom resolveOrCached(String groupId,
                                        String artifactId,
                                        String version,
                                        Map<DependencyCoordinate, ResolvedPom> resolved,
                                        Map<DependencyCoordinate, UnresolvedPom> unresolved) throws IOException {
        DependencyCoordinate coordinates = new DependencyCoordinate(groupId, artifactId, version);
        ResolvedPom pom = resolved.get(coordinates);
        if (pom == null) {
            try {
                pom = resolve(assembleOrCached(groupId,
                        artifactId,
                        version,
                        new HashSet<>(),
                        unresolved), unresolved);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to resolve " + groupId + ":" + artifactId + ":" + version, e);
            }
            resolved.put(coordinates, pom);
        }
        return pom;
    }

    private static Stream<Node> toChildren(Node node) {
        NodeList children = node.getChildNodes();
        return IntStream.iterate(0,
                index -> index < children.getLength(),
                index -> index + 1).mapToObj(children::item);
    }

    private static Stream<Node> toChildren400(Node node, String localName) {
        return toChildren(node).filter(child -> Objects.equals(child.getLocalName(), localName)
                && Objects.equals(child.getNamespaceURI(), NAMESPACE_4_0_0));
    }

    private static Optional<String> toTextChild400(Node node, String localName) {
        return toChildren400(node, localName).map(Node::getTextContent).findFirst();
    }

    private static Map.Entry<DependencyKey, DependencyValue> toDependency400(Node node) {
        return Map.entry(
                new DependencyKey(
                        toTextChild400(node, "groupId").orElseThrow(missing("groupId")),
                        toTextChild400(node, "artifactId").orElseThrow(missing("artifactId")),
                        toTextChild400(node, "type").orElse("jar"),
                        toTextChild400(node, "classifier").orElse(null)),
                new DependencyValue(
                        toTextChild400(node, "version").orElse(null),
                        toTextChild400(node, "scope").orElse(null),
                        toTextChild400(node, "systemPath").orElse(null),
                        toChildren400(node, "exclusions")
                                .findFirst()
                                .map(exclusions -> toChildren400(exclusions, "exclusion")
                                        .map(child -> new DependencyName(
                                                toTextChild400(child, "groupId").orElseThrow(missing("exclusion.groupId")),
                                                toTextChild400(child, "artifactId").orElseThrow(missing("exclusion.artifactId"))))
                                        .toList())
                                .orElse(null),
                        toTextChild400(node, "optional").orElse(null)));
    }

    private static String property(String text, Map<String, String> properties) {
        return property(text, properties, Set.of());
    }

    private static String property(String text, Map<String, String> properties, Set<String> previous) {
        if (text != null && text.contains("$")) {
            Matcher matcher = PROPERTY.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String property = matcher.group(2);
                String replacement = properties.get(property);
                if (replacement == null) {
                    replacement = System.getProperty(property);
                }
                if (replacement == null) {
                    throw new IllegalStateException("Property not defined: " + property);
                } else {
                    HashSet<String> duplicates = new HashSet<>(previous);
                    if (!duplicates.add(property)) {
                        throw new IllegalStateException("Circular property definition of: " + property);
                    }
                    matcher.appendReplacement(sb, property(replacement, properties, duplicates));
                }
            }
            return matcher.appendTail(sb).toString();
        } else {
            return text;
        }
    }

    private static MavenDependencyScope toScope(String scope) {
        return switch (scope) {
            case "compile" -> MavenDependencyScope.COMPILE;
            case "provided" -> MavenDependencyScope.PROVIDED;
            case "runtime" -> MavenDependencyScope.RUNTIME;
            case "test" -> MavenDependencyScope.TEST;
            case "system" -> MavenDependencyScope.SYSTEM;
            case "import" -> MavenDependencyScope.IMPORT;
            case null -> MavenDependencyScope.COMPILE;
            default -> throw new IllegalArgumentException("");
        };
    }

    private static Supplier<IllegalStateException> missing(String property) {
        return () -> new IllegalStateException("Property not defined: " + property);
    }

    private Metadata toMetadata(Map<DependencyName, Metadata> cache, String groupId, String artifactId) throws IOException {
        Metadata metadata = cache.get(new DependencyName(groupId, artifactId));
        if (metadata == null) {
            Document document;
            try (InputStream inputStream = repository.fetchMetadata(groupId, artifactId, null).toInputStream()) {
                document = factory.newDocumentBuilder().parse(inputStream);
            } catch (SAXException | ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
            metadata = switch (document.getDocumentElement().getAttribute("modelVersion")) {
                case "1.1.0" -> {
                    Node versioning = toChildren(document.getDocumentElement())
                            .filter(node -> Objects.equals(node.getLocalName(), "versioning"))
                            .findFirst()
                            .orElseThrow(missing("versioning"));
                    yield new Metadata(
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "latest"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("latest")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "release"))
                                    .findFirst()
                                    .map(Node::getTextContent)
                                    .orElseThrow(missing("release")),
                            toChildren(versioning)
                                    .filter(node -> Objects.equals(node.getLocalName(), "versions"))
                                    .findFirst()
                                    .stream()
                                    .flatMap(MavenPomResolver::toChildren)
                                    .filter(node -> Objects.equals(node.getLocalName(), "version"))
                                    .map(Node::getTextContent)
                                    .toList());
                }
                case null, default -> throw new IllegalStateException("Unknown model version: " +
                        document.getDocumentElement().getAttribute("modelVersion"));
            };
            cache.put(new DependencyName(groupId, artifactId), metadata);
        }
        return metadata;
    }

    private record DependencyKey(String groupId,
                                 String artifactId,
                                 String type,
                                 String classifier) {
        private DependencyKey resolve(Map<String, String> properties) {
            return new DependencyKey(property(groupId, properties),
                    property(artifactId, properties),
                    property(type, properties),
                    property(classifier, properties));
        }
    }

    private record DependencyValue(String version,
                                   String scope,
                                   String systemPath,
                                   List<DependencyName> exclusions,
                                   String optional) {
        private DependencyValue resolve(Map<String, String> properties) {
            return new DependencyValue(property(version, properties),
                    property(scope, properties),
                    property(systemPath, properties),
                    exclusions == null ? null : exclusions.stream().map(exclusion -> new DependencyName(
                            property(exclusion.groupId(), properties),
                            property(exclusion.artifactId(), properties))).toList(),
                    property(optional, properties)
            );
        }

        private DependencyValue with(DependencyValue supplement) {
            if (supplement == null) {
                return this;
            }
            return new DependencyValue(version == null ? supplement.version() : version,
                    scope == null ? supplement.scope() : scope,
                    systemPath == null ? supplement.systemPath() : systemPath,
                    exclusions == null ? supplement.exclusions() : exclusions,
                    optional == null ? supplement.optional() : optional);
        }
    }

    private record DependencyName(String groupId, String artifactId) {
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }

    private record UnresolvedPom(Map<String, String> properties,
                                 Map<DependencyKey, DependencyValue> managedDependencies,
                                 SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ResolvedPom(Map<DependencyKey, DependencyValue> managedDependencies,
                               SequencedMap<DependencyKey, DependencyValue> dependencies) {
    }

    private record ContextualPom(ResolvedPom pom,
                                 boolean root,
                                 MavenDependencyScope scope,
                                 Set<DependencyName> exclusions) {
    }

    private record DependencyResolution(String version,
                                        List<String> versions,
                                        MavenDependencyScope scope,
                                        List<MavenDependencyScope> scopes,
                                        Set<DependencyName> exclusions) {
    }

    private record DependencyEntry(DependencyKey key, int children, String version, MavenDependencyScope scope) {
    }

    private record Metadata(String latest, String release, List<String> versions) {
    }
}
