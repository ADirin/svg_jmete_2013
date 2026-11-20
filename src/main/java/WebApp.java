import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Browser / REST entry point for the Time Calculator.
 * Run this class (instead of SVG_3012_LS_V6) and open http://localhost:8083
 *
 * UnitDao and SVG_3012_LS_V6 live in the default package, so this class does too.
 * We deliberately avoid @SpringBootApplication (its @ComponentScan would scan the
 * whole classpath from the default package) and register the controller explicitly.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(CalculatorController.class)
public class WebApp {

    @Bean
    UnitDao unitDao() {
        return UnitDao.fromEnvironment();
    }

    public static void main(String[] args) {
        SpringApplication.run(WebApp.class, args);
    }
}
