package com.BB465_stuff.Terminal;

/**
 * What the terminal needs from whatever runs its commands: Shell (Shizuku,
 * uid 2000) or SuShell (root, uid 0). Every method is asynchronous - replies
 * come back on the main looper, so a slow command cannot block the UI.
 */
interface Transport {

    /** true when commands will actually run */
    boolean isReady();

    void exec(String cmd);

    /** run a command from a directory */
    void exec(String cmd, String cwd);

    /** run a command and get the output back as a single string */
    void execRaw(String cmd, Shell.Raw r);

    /** run a command with a file on its stdin */
    void execFromStream(java.io.File f, String script) throws Exception;

    /** copy a file to the far side, returns the byte count or an error string */
    String uploadTo(java.io.File f, String remote);

    void psStart(Shell.Raw r);
    void psLine(String cmd, Shell.Raw r);
    void psStop(Shell.Raw r);

    /** one line for the banner, help and info: what we are, and how we got here */
    String describe();

    /** the uid commands run as: 0 for root, 2000 for a shell */
    int uid();
}
