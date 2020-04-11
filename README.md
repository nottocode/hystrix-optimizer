# Hystrix Optimizer

## Usage

* Works for **com.hystrix:hystrix-configurator:0.0.8**

* Add maven dependency
```xml
                <dependency>
                    <groupId>io.phonepe</groupId>
                    <artifactId>hystrix-optimizer</artifactId>
                    <version>1.0.3</version>
                </dependency>

```
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
* Add OptimizerConfig to dropwizard application configuration class
```aidl
    public class AppConfiguration extends Configuration {
      ...
      private OptimizerConfig hystrixOptimizerConfig;
      ...
    }
```

* Add Hystrix optimizer bundle to dropwizard app initialize method
```aidl
    @Override
    public void initialize(Bootstrap<AppConfiguration> bootstrap) {
        ...
               // Add Hystrix optimizer bundle
                bootstrap.addBundle(new HystrixOptimizerBundle<AppConfiguration>() {
                    @Override
                    public HystrixConfig getHystrixConfig(AppConfiguration configuration) {
                        return configuration.getHystrixConfig();
                    }
        
                    @Override
                    public OptimizerConfig getOptimizerConfig(AppConfiguration configuration) {
                        return configuration.getHystrixOptimizerConfig();
                    }
                });
     ...           
    }
```