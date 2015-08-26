/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo.controller;

import javafx.application.Platform;
import net.straylightlabs.archivo.Archivo;
import net.straylightlabs.archivo.model.Recording;
import net.straylightlabs.archivo.model.Tivo;
import net.straylightlabs.archivo.net.MindCommandIdSearch;
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

/**
 * Created by todd on 8/24/15.
 */
public class ArchiveQueueManager implements Runnable {
    private final BlockingQueue<Recording> archiveQueue;
    private final Archivo mainApp;

    private static final int BUFFER_SIZE = 8192;

    public ArchiveQueueManager(Archivo mainApp, BlockingQueue<Recording> queue) {
        this.mainApp = mainApp;
        archiveQueue = queue;
    }

    @Override
    public void run() {
        try {
            while (true) {
                archive(archiveQueue.take());
            }
        } catch (InterruptedException e) {
            Archivo.logger.severe("Interrupted while retrieving next archive task: " + e.getLocalizedMessage());
        }
    }

    private void archive(Recording recording) {
        Archivo.logger.info("Starting archive task for " + recording.getTitle());
        Platform.runLater(() -> mainApp.setStatusText(String.format("Archiving %s...", recording.getTitle())));
        try {
            Tivo tivo = mainApp.getActiveTivo();
            MindCommandIdSearch command = new MindCommandIdSearch(recording, tivo);
            command.executeOn(tivo.getClient());
            URL url = command.getDownloadUrl();
            Archivo.logger.info("URL: " + url);

            // FIXME Make this user-configurable
            Path destination = Paths.get(System.getProperty("user.home"), "download.tivo");
            getRecording(url, destination);
        } catch (IOException e) {
            Archivo.logger.severe("Error fetching recording information: " + e.getLocalizedMessage());
        }
        Platform.runLater(mainApp::clearStatusText);
    }

    private void getRecording(URL url, Path destination) {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
                new UsernamePasswordCredentials("tivo", mainApp.getMak()));
        CookieStore cookieStore = new BasicCookieStore();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .setDefaultCookieStore(cookieStore)
                .build()) {
            HttpGet get = new HttpGet(url.toString());
            // Initial request to set the session cookie
            try (CloseableHttpResponse response = client.execute(get)) {
                response.close(); // Not needed, but clears up a warning
            }
            // Now fetch the file
            try (CloseableHttpResponse response = client.execute(get)) {
                if (response.getStatusLine().getStatusCode() != 200) {
                    Archivo.logger.severe("Error downloading recording: " + response.getStatusLine());
                }

                long estimatedLength = getEstimatedLengthFromHeaders(response);
                double priorPercent = 0;
                try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(destination))) {
                    try (BufferedInputStream inputStream = new BufferedInputStream(response.getEntity().getContent())) {
                        byte[] buffer = new byte[BUFFER_SIZE + 1];
                        long totalBytesRead = 0;
                        for (int bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE);
                             bytesRead >= 0;
                             bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE)) {
                            totalBytesRead += bytesRead;
                            outputStream.write(buffer, 0, bytesRead);
                            double percent = totalBytesRead / (double) estimatedLength;
                            if (percent - priorPercent >= 0.01) {
                                System.out.printf("Read %d bytes of %d expected bytes (%d%%)%n",
                                        totalBytesRead, estimatedLength, (int) (percent * 100));
                                priorPercent = percent;
                            }
                        }
                        System.out.println("Download complete.");
                    } catch (IOException e) {
                        Archivo.logger.severe("Error reading file from network: " + e.getLocalizedMessage());
                    }
                } catch (IOException e) {
                    Archivo.logger.severe("Error creating file: " + e.getLocalizedMessage());
                }
            }
        } catch (IOException e) {
            Archivo.logger.severe("Error downloading recording: " + e.getLocalizedMessage());
        }
    }

    private long getEstimatedLengthFromHeaders(CloseableHttpResponse response) {
        long length = -1;
        for (Header header : response.getAllHeaders()) {
            if (header.getName().equalsIgnoreCase("TiVo-Estimated-Length")) {
                try {
                    length = Long.parseLong(header.getValue());
                } catch (NumberFormatException e) {
                    Archivo.logger.severe(String.format("Error parsing estimated length (%s): %s%n",
                                    header.getValue(), e.getLocalizedMessage())
                    );
                }
            }
        }
        return length;
    }
}