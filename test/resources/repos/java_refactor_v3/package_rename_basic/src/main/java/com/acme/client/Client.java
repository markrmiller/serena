package com.acme.client;

import com.acme.app.Service;

public class Client {
    public int run() {
        return new Service().value();
    }
}
