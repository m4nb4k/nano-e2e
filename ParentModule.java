package lt.bas.nano.service.rest.mobilepayments;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import org.testng.ITestContext;


public class ParentModule extends AbstractModule {

    private ITestContext context;

    @Inject
    public ParentModule(ITestContext context) {
        this.context = context;
    }

    @Override
    protected void configure() {
        bind(ITestContext.class).toInstance(context);
    }
}