package com.bc.arena.concurrency;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Sample usage of completable futures that, given a URL parses its html to find all img tags and proceeds to download
 * all found images.
 */

public class CompletableFuturesImageDownloaderExample {

    private static final Pattern IMG_PATTERN = Pattern.compile(
            "[<]\\s*[iI][mM][gG]\\s*[^>]*[sS][rR][cC]\\s*[=]\\s*['\"]([^'\"]*)['\"][^>]*[>]");

    static final ExecutorService executor = Executors.newFixedThreadPool(12);
    static final HttpClient client = HttpClient.newBuilder().executor(executor).build();
    static final AtomicBoolean done = new AtomicBoolean(false);

    public static void main(String[] args) throws InterruptedException, ExecutionException {

        final var target = "https://blog.sbaldrich.dev/es/blog/2021-01-19-quick-elk-with-docker-compose";
        final var uri = URI.create(target);

        final var client = HttpClient.newBuilder().executor(executor).build();
        final var request = HttpRequest.newBuilder(uri).GET().build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)          // Grab the plain html
                .thenApplyAsync(html -> obtainImageURIsFromHtml(uri, html), executor) // Find all images
                .thenComposeAsync(CompletableFuturesImageDownloaderExample::downloadImages, executor) // and download them
                .exceptionallyAsync(CompletableFuturesImageDownloaderExample::handleException, executor)
                .get();

        System.out.println("Shutting down...");
        executor.shutdown(); // and shut it down after all is done.
    }

    private static List<URI> obtainImageURIsFromHtml(final URI baseUri, final String html) {
        try {
            final var result = new ArrayList<URI>();
            final var matcher = IMG_PATTERN.matcher(html);
            final var baseURL = baseUri.toURL();
            while (matcher.find()) {
                result.add(new URL(baseURL, matcher.group(1)).toURI());
            }
            System.out.printf("Obtained %d images...\n", result.size());
            return result;
        } catch (MalformedURLException | URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String randomFileName(final String extension){
        return String.format("%s.%s", UUID.randomUUID().toString().substring(0, 5), extension);
    }

    private static void copyFile(final InputStream inputStream, final Path target){
        try {
            Files.copy(inputStream, target);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static CompletableFuture<Void> downloadImages(List<URI> uris)  {
        final var tmpFileDir = System.getProperty("java.io.tmpdir");

        final CompletableFuture[] futures = uris.stream().map(uri -> {
            final var request = HttpRequest.newBuilder(uri).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(HttpResponse::body)
                    .thenAcceptAsync(is -> {
                        System.out.printf("[%s] Downloading image...\n", Thread.currentThread().getName());
                        copyFile(is, Paths.get(tmpFileDir, randomFileName("png")));
                        System.out.printf("[%s] Done!\n", Thread.currentThread().getName());
                    }, executor);
        }).toList().toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(futures);
    }

    private static Void handleException(Throwable throwable) {
        System.err.printf("oops: %s", throwable.getMessage());
        return null;
    }
}
