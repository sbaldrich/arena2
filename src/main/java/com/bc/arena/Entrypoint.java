package com.bc.arena;

public class Entrypoint {
    public static void main(String[] args) {
        final var message = String.format("""
                You're running the Arena on java %s
                see individual examples in the subdirectories.
                """, Runtime.version());
        System.out.println(message);
    }
}
