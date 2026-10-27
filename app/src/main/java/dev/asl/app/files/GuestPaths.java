package dev.asl.app.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Maps a path as seen INSIDE a Linux guest ("/etc/hostname") to a real file under the distro's root directory,
 * resolving symlinks the way the guest would: absolute symlink targets are re-rooted at the distro root, and
 * ".." can never climb above it. A guest symlink therefore cannot make the file manager touch Android files.
 *
 * Pure Java (no Android APIs) so it is unit-tested on the JVM.
 */
public final class GuestPaths {
    private static final int MAX_LINK_HOPS = 40;

    private GuestPaths() { }

    /** Lexical normalisation: collapses "//", "." and "..", always returns an absolute path ("/" at minimum). */
    public static String normalize(String guestPath) {
        Deque<String> stack = new ArrayDeque<>();
        for (String c : components(guestPath)) {
            if (c.isEmpty() || c.equals(".")) continue;
            if (c.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(c);
            }
        }
        return join(stack);
    }

    /** Lexical parent of a guest path ("/" is its own parent). */
    public static String parent(String guestPath) {
        String n = normalize(guestPath);
        if (n.equals("/")) return "/";
        int i = n.lastIndexOf('/');
        return i <= 0 ? "/" : n.substring(0, i);
    }

    public static String child(String guestDir, String name) {
        String d = normalize(guestDir);
        return d.equals("/") ? "/" + name : d + "/" + name;
    }

    /**
     * Resolves [guestPath] under [root]. If [followFinal] is false a symlink in the LAST component is returned
     * as-is (used for deleting or inspecting links). Throws IOException on symlink loops.
     */
    public static File resolve(File root, String guestPath, boolean followFinal) throws IOException {
        Deque<String> stack = new ArrayDeque<>();
        Deque<String> pending = new ArrayDeque<>(components(guestPath));
        int hops = 0;
        while (!pending.isEmpty()) {
            String comp = pending.removeFirst();
            if (comp.isEmpty() || comp.equals(".")) continue;
            if (comp.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
                continue;
            }
            if (comp.indexOf('\0') >= 0) throw new IOException("Invalid path");
            File candidate = hostOf(root, stack, comp);
            boolean last = pending.isEmpty();
            if (Files.isSymbolicLink(candidate.toPath()) && (!last || followFinal)) {
                if (++hops > MAX_LINK_HOPS) throw new IOException("Too many levels of symbolic links");
                String target = Files.readSymbolicLink(candidate.toPath()).toString();
                if (target.startsWith("/")) stack.clear();
                List<String> t = components(target);
                for (int i = t.size() - 1; i >= 0; i--) pending.addFirst(t.get(i));
            } else {
                stack.addLast(comp);
            }
        }
        return hostOf(root, stack, null);
    }

    private static File hostOf(File root, Deque<String> stack, String extra) {
        File f = root;
        for (Iterator<String> it = stack.iterator(); it.hasNext(); ) f = new File(f, it.next());
        return extra == null ? f : new File(f, extra);
    }

    private static List<String> components(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.replace('\\', '/').split("/")) out.add(s);
        return out;
    }

    private static String join(Deque<String> stack) {
        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : stack) sb.append('/').append(s);
        return sb.toString();
    }

    /** True if [name] is a single, safe file name (no separators, not "." or ".."). */
    public static boolean isSafeName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0 && name.length() <= 255;
    }
}
