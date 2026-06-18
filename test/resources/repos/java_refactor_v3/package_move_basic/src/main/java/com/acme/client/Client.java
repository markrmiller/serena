package com.acme.client;

import com.acme.app.Service;
import com.acme.app.util.Helper;

public class Client {
    public int run() {
        return new Helper().twice(new Service().value());
    }
}
