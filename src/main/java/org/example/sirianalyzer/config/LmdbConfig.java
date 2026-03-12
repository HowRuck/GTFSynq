package org.example.sirianalyzer.config;

import org.lmdbjava.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.ByteBuffer;

import static org.lmdbjava.Env.create;

@Configuration
public class LmdbConfig {

    @Bean(destroyMethod = "close")
    public Env<ByteBuffer> lmdbEnv() {
        File path = new File("./lmdb-data");

        if (!path.exists() && !path.mkdirs()) {
            throw new IllegalStateException("Failed to create LMDB directory at: " + path.getAbsolutePath());
        }

        return create()
                .setMapSize(1024L * 1024 * 1024) // 1GB
                .setMaxDbs(1)
                .open(path, EnvFlags.MDB_NOSYNC);
    }

    @Bean(destroyMethod = "close")
    public Dbi<ByteBuffer> lmdbDb(Env<ByteBuffer> env) {
        return env.openDbi("gtfs", DbiFlags.MDB_CREATE);
    }
}