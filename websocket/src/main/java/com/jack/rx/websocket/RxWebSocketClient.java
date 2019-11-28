package com.jack.rx.websocket;

import android.annotation.SuppressLint;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;

import java.net.ConnectException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

/**
 * 描述:
 *
 * @author :jack.gu
 * @since : 2019/11/6
 */
public final class RxWebSocketClient {
    private final static String TAG = RxWebSocketClient.class.getName();
    private final static int MAX_RECONNECT = 3;
    private final PublishSubject<WebSocket.READYSTATE> m_reConnectSubject = PublishSubject.create();
    private final PublishSubject<String> m_messageSubject = PublishSubject.create();
    private URI m_serverUri;
    private Draft m_protocolDraft;
    private Map<String, String> m_httpHeaders;
    private int m_connectTimeout = -1;
    private WebSocketClientImpl m_webSocketClient;
    private boolean m_autoReconnect = false;

    public RxWebSocketClient(@NonNull URI serverUri) {
        this.m_serverUri = serverUri;
    }

    public RxWebSocketClient(@NonNull URI serverUri, Draft protocolDraft) {
        this.m_serverUri = serverUri;
        this.m_protocolDraft = protocolDraft;
    }

    public RxWebSocketClient(@NonNull URI serverUri, Map<String, String> httpHeaders) {
        this.m_serverUri = serverUri;
        this.m_httpHeaders = httpHeaders;
    }

    public RxWebSocketClient(@NonNull URI serverUri, Draft protocolDraft, Map<String, String> httpHeaders) {
        this.m_serverUri = serverUri;
        this.m_protocolDraft = protocolDraft;
        this.m_httpHeaders = httpHeaders;
    }

    public RxWebSocketClient(@NonNull URI serverUri, Draft protocolDraft, Map<String, String> httpHeaders, int connectTimeout) {
        this.m_serverUri = serverUri;
        this.m_protocolDraft = protocolDraft;
        this.m_httpHeaders = httpHeaders;
        this.m_connectTimeout = connectTimeout;
    }

    public Observable<String> onMessageObservable() {
        return m_messageSubject;
    }

    @SuppressLint("CheckResult")
    public Observable<Boolean> connect() {
        if (m_webSocketClient == null) {
            return connect0().map(pair -> {
                m_webSocketClient = pair.first;
                if (m_autoReconnect) {
                    //noinspection ResultOfMethodCallIgnored
                    m_webSocketClient.onEventObservable()
                            .filter(o -> o instanceof Exception)
                            .take(1)
                            .concatMap(o -> connect0()
                                    .retryWhen(throwableObservable -> throwableObservable
                                            .zipWith(Observable.range(1, MAX_RECONNECT), (throwable, integer) -> integer)
                                            .flatMap(i -> Observable.timer(i, TimeUnit.SECONDS)))
                                    .doOnNext(pair1 -> {
                                        m_webSocketClient = pair1.first;
                                        m_reConnectSubject.onNext(WebSocket.READYSTATE.OPEN);
                                    })
                                    .doOnError(throwable -> {
                                        m_webSocketClient = null;
                                        m_reConnectSubject.onNext(WebSocket.READYSTATE.CLOSED);
                                    }))
                            .subscribe(pair1 -> Log.i(TAG, String.format("reconnect ok : %s", pair1.toString())),
                                    throwable -> Log.e(TAG, String.format("reconnect failed : %s", throwable.getMessage())));
                }
                return true;
            });
        } else {
            if (m_webSocketClient.isOpen()) {
                return Observable.just(true);
            } else if (m_autoReconnect) {
                return m_reConnectSubject.take(1).map(readystate -> readystate == WebSocket.READYSTATE.OPEN);
            } else {
                return Observable.just(false);
            }
        }
    }

    public Observable<Boolean> close() {
        if (m_webSocketClient == null) {
            return Observable.just(true);
        } else if (m_webSocketClient.isOpen()) {
            return close0();
        } else if (m_autoReconnect) {
            return m_reConnectSubject.take(1).concatMap(readystate -> {
                if (readystate == WebSocket.READYSTATE.OPEN) {
                    return close0();
                } else {
                    return Observable.just(true);
                }
            });
        } else {
            return Observable.just(true);
        }
    }

    @SuppressLint("CheckResult")
    private Observable<Pair<WebSocketClientImpl, WebSocket.READYSTATE>> connect0() {
        return Observable.just(createWebSocketClientImpl())
                .concatMap(webSocketClient -> webSocketClient.onEventObservable()
                        .doOnSubscribe(disposable -> webSocketClient.connect())
                        .take(1)
                        .map(o -> {
                            if (o instanceof ServerHandshake) {
                                ServerHandshake serverHandshake = (ServerHandshake) o;
                                if (101 == serverHandshake.getHttpStatus()) {
                                    webSocketClient.onEventObservable()
                                            .filter(o1 -> o1 instanceof String)
                                            .map(o1 -> (String) o1)
                                            .subscribe(m_messageSubject);
                                    return Pair.create(webSocketClient, webSocketClient.getReadyState());
                                } else {
                                    throw new ConnectException(serverHandshake.getHttpStatusMessage());
                                }
                            } else {
                                Exception e = (Exception) o;
                                throw new ConnectException(e.getMessage());
                            }
                        }));
    }

    private Observable<Boolean> close0() {
        return m_webSocketClient.onEventObservable()
                .doOnSubscribe(disposable -> m_webSocketClient.close())
                .filter(o -> o instanceof Object[])
                .take(1)
                .map(o -> {
                    Object[] objects = (Object[]) o;
                    int code = (int) objects[0];
                    String reason = (String) objects[1];
                    boolean remote = (boolean) objects[2];
                    return 1000 == code;
                });
    }

    private WebSocketClientImpl createWebSocketClientImpl() {
        if (m_protocolDraft == null && m_httpHeaders == null) {
            return new WebSocketClientImpl(m_serverUri);
        } else if (m_protocolDraft == null) {
            return new WebSocketClientImpl(m_serverUri, m_httpHeaders);
        } else if (m_httpHeaders == null) {
            return new WebSocketClientImpl(m_serverUri, m_protocolDraft);
        } else if (m_connectTimeout == -1) {
            return new WebSocketClientImpl(m_serverUri, m_protocolDraft, m_httpHeaders);
        } else {
            return new WebSocketClientImpl(m_serverUri, m_protocolDraft, m_httpHeaders, m_connectTimeout);
        }
    }
}