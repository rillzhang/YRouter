package com.ysbing.yrouter.core.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;


/**
 * 提取dex文件，进行dex解析
 *
 * @author ysbing
 */
public class MakeJarUtil {

    public static void collectJavaFile(String javaPath, List<String> files) {
        File file = new File(javaPath);
        if (file.isDirectory()) {
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File listFile : listFiles) {
                    collectJavaFile(listFile.getAbsolutePath(), files);
                }
            }
        } else if (file.isFile()) {
            String fileName = file.getName();
            String suffix = fileName.substring(fileName.lastIndexOf(".") + 1);
            if ("java".equals(suffix)) {
                files.add(file.getAbsolutePath());
            }
        }
    }

    public static void buildJavaClass(String javaPath, String classPath, String[] buildTools) {
        List<String> files = new ArrayList<>();
        collectJavaFile(javaPath, files);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromStrings(files);
        List<String> options = new ArrayList<>();
        StringBuilder buildToolStrBuilder = new StringBuilder();
        for (String buildTool : buildTools) {
            buildToolStrBuilder.append(buildTool).append(";");
        }
        if (buildToolStrBuilder.toString().endsWith(";")) {
            buildToolStrBuilder = new StringBuilder(buildToolStrBuilder.substring(0, buildToolStrBuilder.length() - 1));
        }
        String buildToolStr = buildToolStrBuilder.toString();
        if (!buildToolStr.equals("")) {
            options.add("-bootclasspath");
            options.add(buildToolStr);
        }
        options.add("-d");
        options.add(classPath);
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, options, null, javaFiles);
        task.call();
        try {
            fileManager.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void buildKotlinClass(String kotlinPath, String classPath, String[] buildTools) {
        boolean result = JvmCompile.INSTANCE.exe(new File(kotlinPath), new File(classPath), buildTools);
        System.out.println("buildKotlinClass:" + kotlinPath + ":" + result);
    }

    public static void buildJar(File dir, File zipFile) throws IOException {
        if (!zipFile.getParentFile().exists()) {
            if (!zipFile.getParentFile().mkdirs()) {
                return;
            }
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        JarOutputStream target = new JarOutputStream(new FileOutputStream(zipFile), manifest);
        add(dir, target, "");
        target.close();
    }

    private static void add(File source, JarOutputStream target, String path) throws IOException {
        BufferedInputStream in = null;
        try {
            if (source.isDirectory()) {
                File[] listFiles = source.listFiles();
                if (listFiles != null) {
                    for (File nestedFile : listFiles) {
                        String newPath = path;
                        if (!path.equals("")) {
                            newPath += File.separator;
                        }
                        newPath += nestedFile.getName();
                        add(nestedFile, target, newPath);
                    }
                }
                return;
            }
            String fileName = source.getName();
            if (!(fileName.endsWith(".class") ||
                    fileName.endsWith(".kt") ||
                    fileName.endsWith(".java"))) {
                return;
            }
            JarEntry entry = new JarEntry(path.replace("\\", "/"));
            entry.setTime(source.lastModified());
            target.putNextEntry(entry);
            in = new BufferedInputStream(new FileInputStream(source));
            byte[] buffer = new byte[1024];
            while (true) {
                int count = in.read(buffer);
                if (count == -1)
                    break;
                target.write(buffer, 0, count);
            }
            target.closeEntry();
        } finally {
            if (in != null)
                in.close();
        }
    }
}