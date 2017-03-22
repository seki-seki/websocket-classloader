package net.unit8.wscl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.fressian.FressianReader;
import org.fressian.FressianWriter;
import org.fressian.handlers.ILookup;
import org.fressian.handlers.ReadHandler;
import org.fressian.handlers.WriteHandler;
import org.fressian.impl.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.unit8.wscl.dto.ResourceRequest;
import net.unit8.wscl.dto.ResourceResponse;
import net.unit8.wscl.handler.ResourceRequestWriteHandler;
import net.unit8.wscl.handler.ResourceResponseReadHandler;
import net.unit8.wscl.util.FressianUtils;
import net.unit8.wscl.util.PropertyUtils;

/**
 * @author kawasima
 */
@ClientEndpoint
public class ClassLoaderEndpoint extends Endpoint {
    private static final Logger logger = LoggerFactory.getLogger(ClassLoaderEndpoint.class);

    private Session session;
    private final ConcurrentMap<String, BlockingQueue<ResourceResponse>> waitingResponses = new ConcurrentHashMap<>();

    public ClassLoaderEndpoint () {
    }

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        this.session = session;
        session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
            @Override
            public void onMessage(ByteBuffer buf) {
                try {
                    FressianReader reader = new FressianReader(new ByteBufferInputStream(buf), new ILookup<Object, ReadHandler>() {
                        @Override
                        public ReadHandler valAt(Object key) {
                            if (key.equals(ResourceResponse.class.getName()))
                                return new ResourceResponseReadHandler();
                            else
                            {
                                logger.debug("Invalid key at read");
                                return null;
                            }
                        }
                    });

                    Object obj = reader.readObject();
                    if (obj instanceof ResourceResponse) {
                        ResourceResponse response = (ResourceResponse) obj;
                        BlockingQueue<ResourceResponse> queue = waitingResponses.get(response.getResourceName());
                        if (queue != null) {
                            queue.offer(response);
                        }
                        else logger.warn("queue is null");
                    } else {
                        logger.warn("Fressian read response: " + obj + "(" + obj.getClass() + ")");
                    }
                } catch (IOException ex) {
                    logger.warn("read response error", ex);
                }
            }
        });
    }

    public ResourceResponse request(ResourceRequest request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FressianWriter fw = new FressianWriter(baos, new ILookup<Class, Map<String, WriteHandler>>() {
            @Override
            public Map<String, WriteHandler> valAt(Class key) {
                if (key.equals(ResourceRequest.class)) {
                    return FressianUtils.map(ResourceRequest.class.getName(),
                            new ResourceRequestWriteHandler());
                } else {
                    logger.debug("Invalid key at write");
                    return null;
                }
            }
        });
        fw.writeObject(request);

        logger.debug("fetch class:" + request.getResourceName() + ":" + request.getClassLoaderId());

        waitingResponses.putIfAbsent(request.getResourceName(), new ArrayBlockingQueue<ResourceResponse>(10));
        BlockingQueue<ResourceResponse> queue = waitingResponses.get(request.getResourceName());
        try {
            session.getAsyncRemote().sendBinary(ByteBuffer.wrap(baos.toByteArray()));
            logger.debug("beforesize" +queue.size());
            ResourceResponse response = queue.poll(PropertyUtils.getLongSystemProperty("wscl.timeout", 5000), TimeUnit.MILLISECONDS);
            logger.debug("aftersize" +queue.size());

            if (response == null)
                throw new IOException("WebSocket request error." + request.getResourceName());
            return response;
        } catch(InterruptedException ex) {
            throw new IOException("Interrupted in waiting for request." + request.getResourceName(), ex);
        } finally {
            synchronized (waitingResponses) {
                while (!queue.isEmpty()) {
                    try {
                        waitingResponses.wait();
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                waitingResponses.remove(request.getResourceName());
            }
        }
    }

    public void close() throws IOException {
        if (session != null && session.isOpen()) {
            session.close();
            session = null;
        }
    }
}
