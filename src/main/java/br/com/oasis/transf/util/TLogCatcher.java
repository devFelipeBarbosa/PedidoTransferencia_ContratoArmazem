package br.com.oasis.transf.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TLogCatcher {

    private static final DateTimeFormatter DT_FORMAT =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public static void logError(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write(TLogType.ERROR, sw.toString());
    }

    public static void logError(String infoMessage, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write(TLogType.ERROR, infoMessage + "\nStackTrace:\n" + sw);
    }

    public static void logError(String error) {
        write(TLogType.ERROR, error);
    }

    public static void logInfo(String info) {
        write(TLogType.INFO, info);
    }

    private static void write(TLogType type, String message) {
        String directory = TLogConfiguration.getPath();
        String name = TLogConfiguration.getFileName();
        if (directory == null || name == null) {
            System.err.println("TLogCatcher: path/fileName nao configurados.");
            return;
        }
        new File(directory).mkdirs();
        String path = directory + "/log" + LocalDate.now() + "-" + name + ".txt";
        String line = LocalDateTime.now().format(DT_FORMAT)
            + " - t[" + Thread.currentThread().getId() + "] - ["
            + type + "] - " + message + "\n";
        try (FileWriter fw = new FileWriter(path, true)) {
            fw.write(line);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
