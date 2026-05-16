package br.com.oasis.transf.util;

public class TLogConfiguration {

    private static ThreadLocal<String> path = new ThreadLocal<String>();
    private static ThreadLocal<String> fileName = new ThreadLocal<String>();

    public static String getPath() {
	return path.get();
    }

    public static void setPath(String path) {
	TLogConfiguration.path.set(path);
    }

    public static String getFileName() {
	return fileName.get();
    }

    public static void setFileName(String fileName) {
	TLogConfiguration.fileName.set(fileName);
    }

    public static void clear() {
	path.remove();
	fileName.remove();
    }
}
