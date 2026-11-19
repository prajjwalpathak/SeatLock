package testsupport;

import com.seatlock.realtime.SeatEventMessage;
import java.util.concurrent.BlockingQueue;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;

/**
 * SUBSCRIBE has no acknowledgement on the simple broker, so tests wait until the instance's broker has
 * registered the subscription before they trigger events.
 */
public final class BrokerSubscriptions {

    private BrokerSubscriptions() {
    }

    /** Subscribes {@code client} (connected to the instance behind {@code app}) and waits until it counts. */
    public static BlockingQueue<SeatEventMessage> subscribe(ApplicationContext app, StompTestClient client,
                                                            String destination) throws InterruptedException {
        SimpleBrokerMessageHandler broker = app.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);
        int before = count(broker, destination);
        BlockingQueue<SeatEventMessage> queue = client.subscribe(destination);
        long deadline = System.nanoTime() + StompTestClient.TIMEOUT.toNanos();
        while (count(broker, destination) <= before) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("subscription to " + destination + " not registered in time");
            }
            Thread.sleep(20);
        }
        return queue;
    }

    private static int count(SimpleBrokerMessageHandler broker, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return broker.getSubscriptionRegistry().findSubscriptions(probe).size();
    }
}
