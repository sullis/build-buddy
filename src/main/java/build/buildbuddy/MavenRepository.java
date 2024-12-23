package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class MavenRepository implements Repository {

    private final URI root;

    public MavenRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        root = URI.create(environment == null ? "https://repo1.maven.org/maven2" : environment);
    }

    public MavenRepository(URI root) {
        this.root = root;
    }

    @Override
    public InputStream download(String coordinate) throws IOException {
        String[] elements = coordinate.split(":");
        return switch (elements.length) {
            case 4 -> download(elements[0], elements[1], elements[2], null, "jar");
            case 5 -> download(elements[0], elements[1], elements[2], null, elements[3]);
            case 6 -> download(elements[0], elements[1], elements[2], elements[3], elements[4]);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
        };
    }

    public InputStream download(String groupId,
                                String artifactId,
                                String version,
                                String classifier,
                                String extension) throws IOException {
        return root.resolve(root.getPath()
                + (root.getPath().endsWith("/") ? "" : "/") + groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + extension).toURL().openConnection().getInputStream();
    }
}