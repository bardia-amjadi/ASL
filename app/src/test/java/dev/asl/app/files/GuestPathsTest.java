package dev.asl.app.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

public class GuestPathsTest {

    private static File newRoot() throws IOException {
        File root = Files.createTempDirectory("guestroot").toFile();
        new File(root, "usr/bin").mkdirs();
        new File(root, "etc").mkdirs();
        Files.write(new File(root, "etc/hostname").toPath(), "box".getBytes());
        Files.write(new File(root, "usr/bin/sh").toPath(), "#!".getBytes());
        Files.createSymbolicLink(new File(root, "bin").toPath(), new File("usr/bin").toPath());   // relative
        Files.createSymbolicLink(new File(root, "cfg").toPath(), new File("/etc").toPath());      // absolute
        Files.createSymbolicLink(new File(root, "escape").toPath(), new File("../../../../../tmp").toPath());
        Files.createSymbolicLink(new File(root, "loop1").toPath(), new File("loop2").toPath());
        Files.createSymbolicLink(new File(root, "loop2").toPath(), new File("loop1").toPath());
        return root;
    }

    @Test public void normalizeCollapsesDotsAndSlashes() {
        assertEquals("/", GuestPaths.normalize(""));
        assertEquals("/", GuestPaths.normalize("/"));
        assertEquals("/a/c", GuestPaths.normalize("//a/./b/../c/"));
        assertEquals("/etc", GuestPaths.normalize("/../../etc"));
    }

    @Test public void parentAndChild() {
        assertEquals("/", GuestPaths.parent("/"));
        assertEquals("/", GuestPaths.parent("/etc"));
        assertEquals("/usr", GuestPaths.parent("/usr/bin"));
        assertEquals("/etc/x", GuestPaths.child("/etc", "x"));
        assertEquals("/x", GuestPaths.child("/", "x"));
    }

    @Test public void resolvesPlainAndRelativeSymlinks() throws IOException {
        File root = newRoot();
        assertEquals(new File(root, "etc/hostname"), GuestPaths.resolve(root, "/etc/hostname", true));
        assertEquals(new File(root, "usr/bin/sh"), GuestPaths.resolve(root, "/bin/sh", true));
    }

    @Test public void absoluteSymlinksAreReRootedInsideTheDistro() throws IOException {
        File root = newRoot();
        assertEquals(new File(root, "etc/hostname"), GuestPaths.resolve(root, "/cfg/hostname", true));
        assertTrue(new File(root, "etc").equals(GuestPaths.resolve(root, "/cfg", true)));
    }

    @Test public void finalSymlinkIsKeptWhenNotFollowing() throws IOException {
        File root = newRoot();
        assertEquals(new File(root, "cfg"), GuestPaths.resolve(root, "/cfg", false));
        assertEquals(new File(root, "etc"), GuestPaths.resolve(root, "/cfg", true));
    }

    @Test public void dotDotAndSymlinksCannotEscapeTheRoot() throws IOException {
        File root = newRoot();
        String rootPath = root.getCanonicalPath();
        File a = GuestPaths.resolve(root, "/../../../etc/passwd", true);
        assertTrue(a.getPath().startsWith(root.getPath()));
        File b = GuestPaths.resolve(root, "/escape/secret", true);
        assertTrue(b.getPath().startsWith(root.getPath()));
        assertFalse(b.getCanonicalPath().equals("/tmp/secret"));
        assertTrue(a.getCanonicalPath().startsWith(rootPath));
    }

    @Test public void symlinkLoopsAreDetected() throws IOException {
        File root = newRoot();
        boolean threw = false;
        try {
            GuestPaths.resolve(root, "/loop1", true);
        } catch (IOException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test public void safeNames() {
        assertTrue(GuestPaths.isSafeName("notes.txt"));
        assertFalse(GuestPaths.isSafeName(".."));
        assertFalse(GuestPaths.isSafeName("a/b"));
        assertFalse(GuestPaths.isSafeName(""));
        assertFalse(GuestPaths.isSafeName("x\0y"));
    }
}
