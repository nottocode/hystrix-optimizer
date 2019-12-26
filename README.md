# Hystrix Optimizer

## Usage

* Works for **com.hystrix:hystrix-configurator:0.0.8**
* Disable Metrics Publisher in HystrixBundle for your dropwizard app

```aidl
       // Add the Hystrix Bundle with metrics publisher disabled
        bootstrap.addBundle(HystrixBundle.builder()
                .disableStreamServletInAdminContext()
                .disableMetricsPublisher()
                .withApplicationStreamPath("/hystrix.stream")
                .build()
        );
```

* Remove HystrixConfigurationFactory init, taken care of by hystrix optimizer
```aidl
        // Remove following from run method, initialized by hystrix optimizer with provided hystrix config
        HystrixConfigurationFactory.init(hystrixConfig);

```