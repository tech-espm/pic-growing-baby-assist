package br.espm.processaaudio;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.widget.RemoteViews;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

// Referências extras:
// https://developer.android.com/reference/android/media/MediaCodec
// https://developer.android.com/reference/android/media/MediaCodec.BufferInfo.html
// https://developer.android.com/reference/android/media/MediaExtractor
// https://developer.android.com/reference/android/media/MediaFormat

public class Servico extends Service implements Runnable, Handler.Callback {

	public interface Callback {
		void onEstadoAlterado(int estado);
		void onErroDecodificacao();
		// @@@ MSG_DADOS não será necessária no programa de verdade!
		void onDados(int pacotesLidos);
	}

	private static final String CHANNEL_GROUP_ID = "processaaudiog";
	private static final String CHANNEL_ID = "processaaudio";

	private static Servico servico;
	private static Callback callback;

	public static final int ESTADO_NOVO = 0;
	public static final int ESTADO_INICIANDO = 1;
	public static final int ESTADO_INICIADO = 2;
	public static final int ESTADO_PARANDO = 3;
	private static volatile int estado = ESTADO_NOVO;
	private static volatile Uri audioUri;

	private static boolean channelCriado = false;

	private static final int MSG_ERRO = 1;
	private static final int MSG_TERMINADO = 2;
	// @@@ MSG_DADOS não será necessária no programa de verdade!
	private static final int MSG_DADOS = 3;

	private Thread thread;
	private short[] buffer;
	private MediaExtractor mediaExtractor;
	private MediaCodec mediaCodec;
	private PowerManager.WakeLock wakeLock;
	private Handler handler;

	// Para decodificação do áudio
	private short[] bufferTemporarioEstereo;
	private MediaCodec.BufferInfo bufferInfo;
	private int canais, sampleRate, proximoOutputBuffer;
	private ByteBuffer[] inputBuffers;
	private ShortBuffer[] outputBuffers;
	private boolean inputTerminado, outputTerminado;

	public static void iniciarServico(Application application, Uri audioUri) {
		if (estado != ESTADO_NOVO)
			return;

		estado = ESTADO_INICIANDO;
		Servico.audioUri = audioUri;

		notificarEstado();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			application.startForegroundService(new Intent(application, Servico.class));
		else
			application.startService(new Intent(application, Servico.class));
	}

	public static void pararServico() {
		if (servico != null && estado == ESTADO_INICIADO) {
			estado = ESTADO_PARANDO;

			servico.stopForeground(true);
			servico.stopSelf();
			servico = null;

			notificarEstado();
		}
	}

	public static void setCallback(Callback callback) {
		Servico.callback = callback;
	}

	public static int getEstado() {
		return estado;
	}

	private static void notificarEstado() {
		if (callback != null)
			callback.onEstadoAlterado(estado);
	}

	private static void notificarErroDecodificacao() {
		if (callback != null)
			callback.onErroDecodificacao();
	}

	private static void notificarDados(int pacotesLidos) {
		// @@@ MSG_DADOS não será necessária no programa de verdade!
		if (callback != null && estado == ESTADO_INICIADO)
			callback.onDados(pacotesLidos);
	}

	@TargetApi(Build.VERSION_CODES.O)
	private static void criarChannel(Application application) {
		if (channelCriado)
			return;

		NotificationManager notificationManager = (NotificationManager)application.getSystemService(NOTIFICATION_SERVICE);

		if (notificationManager == null)
			return;

		String appName = application.getText(R.string.app_name).toString();

		notificationManager.createNotificationChannelGroup(new NotificationChannelGroup(CHANNEL_GROUP_ID, appName));

		NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, appName, NotificationManager.IMPORTANCE_LOW);
		notificationChannel.setGroup(CHANNEL_GROUP_ID);
		notificationChannel.enableLights(false);
		notificationChannel.enableVibration(false);
		notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationChannel.setShowBadge(false);
		notificationManager.createNotificationChannel(notificationChannel);

		channelCriado = true;
	}

	@TargetApi(Build.VERSION_CODES.O)
	private static Notification criarNotificacaoComChannel(Application application) {
		return new Notification.Builder(application, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).build();
	}

	@SuppressLint("WakelockTimeout")
	@Override
	public void onCreate() {
		super.onCreate();

		estado = ESTADO_INICIADO;
		servico = this;

		Application application = servico.getApplication();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			criarChannel(application);

		Notification notification = ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ? criarNotificacaoComChannel(application) : new Notification());
		notification.icon = R.drawable.ic_notification;
		notification.when = 0;
		notification.flags = Notification.FLAG_FOREGROUND_SERVICE;

		Intent intent = new Intent(application, MainActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		notification.contentIntent = PendingIntent.getActivity(application, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
		notification.contentView = new RemoteViews(application.getPackageName(), R.layout.notification_layout);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
			notification.visibility = Notification.VISIBILITY_PUBLIC;

		startForeground(1, notification);

		// Independente do tamanho do buffer interno do gravador, iremos utilizar um buffer com 1024 amostras
		buffer = new short[1024];

		// Para decodificação do áudio
		bufferTemporarioEstereo = new short[buffer.length * 2];
		bufferInfo = new MediaCodec.BufferInfo();
		bufferInfo.flags = 0;
		bufferInfo.offset = 0;
		bufferInfo.size = 0;
		canais = 0;
		sampleRate = 0;
		proximoOutputBuffer = -1;
		inputBuffers = null;
		outputBuffers = null;
		inputTerminado = false;
		outputTerminado = false;

		handler = new Handler(this);

		thread = new Thread(this, "Thread de gravação");
		thread.start();

		PowerManager powerManager = (PowerManager)application.getSystemService(POWER_SERVICE);
		if (powerManager != null) {
			wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "WakeLock de gravação");
			wakeLock.setReferenceCounted(false);
			wakeLock.acquire();
		}

		notificarEstado();
	}

	@Override
	public void onDestroy() {
		servico = null;
		estado = ESTADO_NOVO;

		if (thread != null) {
			thread.interrupt();
			try {
				thread.join();
			} catch (InterruptedException e) {
				// Apenas ignora...
			}
			thread = null;
		}

		if (mediaExtractor != null) {
			try {
				mediaExtractor.release();
			} catch (Throwable e) {
				// Apenas ignora...
			}
			mediaExtractor = null;
		}

		if (mediaCodec != null) {
			try {
				mediaCodec.stop();
			} catch (Throwable e) {
				// Apenas ignora...
			}
			try {
				mediaCodec.release();
			} catch (Throwable e) {
				// Apenas ignora...
			}
			mediaCodec = null;
		}

		buffer = null;

		// Para decodificação do áudio
		bufferTemporarioEstereo = null;
		bufferInfo = null;
		inputBuffers = null;
		outputBuffers = null;

		handler = null;

		if (wakeLock != null) {
			wakeLock.release();
			wakeLock = null;
		}

		notificarEstado();

		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public boolean handleMessage(Message msg) {
		switch (msg.what) {
		case MSG_ERRO:
			pararServico();
			notificarErroDecodificacao();
			break;
		case MSG_TERMINADO:
			pararServico();
			break;
		case MSG_DADOS:
			// @@@ MSG_DADOS não será necessária no programa de verdade!
			notificarDados(msg.arg1);
			break;
		default:
			return false;
		}
		return true;
	}

	private void prepararOutputBuffers() {
		ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
		this.outputBuffers = new ShortBuffer[outputBuffers.length];
		for (int i = outputBuffers.length - 1; i >= 0; i--)
			this.outputBuffers[i] = outputBuffers[i].asShortBuffer();
	}

	private boolean criarDecodificador() {
		mediaExtractor = new MediaExtractor();
		try {
			mediaExtractor.setDataSource(getApplication(), audioUri, null);
			int numTracks = mediaExtractor.getTrackCount();
			int i;
			for (i = 0; i < numTracks; i++) {
				MediaFormat format = mediaExtractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("audio/")) {
					// Só nos interessa uma faixa de áudio
					mediaExtractor.selectTrack(i);
					canais = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
					sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);

					// Configura o codec assim que descobrimos que o formato
					// do arquivo era compatível
					mediaCodec = MediaCodec.createDecoderByType(mime);
					mediaCodec.configure(format, null, null, 0);
					mediaCodec.start();
					inputBuffers = mediaCodec.getInputBuffers();
					prepararOutputBuffers();
					break;
				}
			}
			if (i >= numTracks) {
				// Depois de procurar, não conseguimos encontrar
				// nenhuma faixa de áudio no arquivo...
				handler.sendEmptyMessage(MSG_ERRO);
				return false;
			}
			return true;
		} catch (IOException e) {
			// Algo saiu errado!
			handler.sendEmptyMessage(MSG_ERRO);
			return false;
		}
	}

	private boolean decodificarProximoBuffer() {
		if (outputTerminado)
			return true;

		if (!inputTerminado) {
			int proximoInputBuffer = mediaCodec.dequeueInputBuffer(0);
			if (proximoInputBuffer >= 0) {
				// Lê os dados codificados do arquivo
				int bytesLidos = mediaExtractor.readSampleData(inputBuffers[proximoInputBuffer], 0);
				if (bytesLidos < 0) {
					inputTerminado = true;
					mediaCodec.queueInputBuffer(proximoInputBuffer, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
				} else {
					mediaCodec.queueInputBuffer(proximoInputBuffer, 0, bytesLidos, 0, 0);
					// Apesar da documentação pedir para utilizar o valor de retorno
					// de advance() para controlar o final do arquivo, às vezes advance()
					// retorna false no meio do arquivo, em alguns dispositivos...
					mediaExtractor.advance();
				}
			}
		}

		if (proximoOutputBuffer < 0) {
			bufferInfo.flags = 0;
			bufferInfo.offset = 0;
			bufferInfo.size = 0;
			proximoOutputBuffer = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
			if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
				outputTerminado = true;
			if (proximoOutputBuffer < 0) {
				switch (proximoOutputBuffer) {
				case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
					MediaFormat format = mediaCodec.getOutputFormat();
					canais = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
					sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
					break;
				case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
					inputBuffers = mediaCodec.getInputBuffers();
					prepararOutputBuffers();
					break;
				}
				return false;
			}
		}

		return true;
	}

	private int preencherBufferMono(int amostrasPreenchidasNoBuffer) {
		// bufferInfo.size está em bytes, mas queremos em amostras (16 bits)
		int amostrasDisponiveis = (bufferInfo.size >> 1);
		int amostrasVaziasNoBuffer = buffer.length - amostrasPreenchidasNoBuffer;

		if (amostrasDisponiveis > amostrasVaziasNoBuffer)
			amostrasDisponiveis = amostrasVaziasNoBuffer;

		// Deixa o buffer de saída na posição correta (em shorts, não em bytes)
		outputBuffers[proximoOutputBuffer].limit((bufferInfo.offset + bufferInfo.size) >> 1);
		outputBuffers[proximoOutputBuffer].position(bufferInfo.offset >> 1);
		outputBuffers[proximoOutputBuffer].get(buffer, amostrasPreenchidasNoBuffer, amostrasDisponiveis);

		// Desconta o que já foi lido (em bytes)
		int bytesConsumidos = amostrasDisponiveis << 1;
		bufferInfo.offset += bytesConsumidos;
		bufferInfo.size -= bytesConsumidos;

		return amostrasPreenchidasNoBuffer + amostrasDisponiveis;
	}

	private int preencherBufferEstereo(int amostrasPreenchidasNoBuffer) {
		// Aqui, diferente de preencherBufferMono(), as informações em
		// bufferInfo se referem a dois canais!

		// bufferInfo.size está em bytes, mas queremos em amostras (16 bits)
		int amostrasDisponiveis = (bufferInfo.size >> 2);
		int amostrasVaziasNoBuffer = buffer.length - amostrasPreenchidasNoBuffer;

		if (amostrasDisponiveis > amostrasVaziasNoBuffer)
			amostrasDisponiveis = amostrasVaziasNoBuffer;

		// Deixa o buffer de saída na posição correta (em shorts, não em bytes)
		outputBuffers[proximoOutputBuffer].limit((bufferInfo.offset + bufferInfo.size) >> 1);
		outputBuffers[proximoOutputBuffer].position(bufferInfo.offset >> 1);
		outputBuffers[proximoOutputBuffer].get(bufferTemporarioEstereo, amostrasPreenchidasNoBuffer << 1, amostrasDisponiveis << 1);

		// Desconta o que já foi lido (em bytes)
		int bytesConsumidos = amostrasDisponiveis << 2;
		bufferInfo.offset += bytesConsumidos;
		bufferInfo.size -= bytesConsumidos;

		// Como a saída era estéreo, vamos converter para mono da
		// forma mais simples possível: tirando a média
		for (int i = amostrasPreenchidasNoBuffer << 1; amostrasDisponiveis > 0; i += 2, amostrasDisponiveis--, amostrasPreenchidasNoBuffer++)
			buffer[amostrasPreenchidasNoBuffer] = (short)(((int)bufferTemporarioEstereo[i] + (int)bufferTemporarioEstereo[i + 1]) >> 1);

		return amostrasPreenchidasNoBuffer;
	}

	@SuppressWarnings("deprecation")
	@Override
	public void run() {
		if (!criarDecodificador())
			return;

		// @@@ MSG_DADOS não será necessária no programa de verdade!
		int pacotesLidos = 0;

		int amostrasPreenchidasNoBuffer = 0;
		boolean terminado = false;

		while (estado == ESTADO_INICIADO && !terminado) {
			try {
				if (!decodificarProximoBuffer())
					continue;
			} catch (Throwable e) {
				// Algo saiu errado!
				handler.sendEmptyMessage(MSG_ERRO);
				return;
			}

			if (bufferInfo.size > 0 && proximoOutputBuffer >= 0) {
				// Copia algumas amostras do canal esquerdo do buffer para
				// dentro do nosso buffer (se for estéreo, simplesmente descarta
				// o canal direito)
				if (canais == 1) {
					amostrasPreenchidasNoBuffer = preencherBufferMono(amostrasPreenchidasNoBuffer);
				} else if (canais == 2) {
					amostrasPreenchidasNoBuffer = preencherBufferEstereo(amostrasPreenchidasNoBuffer);
				} else {
					// Surround não é suportado!
					handler.sendEmptyMessage(MSG_ERRO);
					return;
				}
			}

			if (bufferInfo.size <= 0) {
				if (proximoOutputBuffer >= 0) {
					// Libera para preencher o próximo buffer de saída só quando
					// todos os dados tiverem sido consumidos
					mediaCodec.releaseOutputBuffer(proximoOutputBuffer, false);
					proximoOutputBuffer = -1;
				}

				if (outputTerminado) {
					// Preenche o restante do nosso buffer com 0, para poder
					// processar o último pacote, e encerra!
					for (int i = buffer.length - 1; i >= amostrasPreenchidasNoBuffer; i--)
						buffer[i] = 0;
					amostrasPreenchidasNoBuffer = buffer.length;
					terminado = true;
				}
			}

			if (estado == ESTADO_INICIADO && amostrasPreenchidasNoBuffer == buffer.length) {
				amostrasPreenchidasNoBuffer = 0;

				// Processa as amostras em buffer...
				// Se for fazer o código em C++, em vez de short[], buffer deveria ser um ByteBuffer!!!

				// @@@ MSG_DADOS não será necessária no programa de verdade!
				pacotesLidos++;
				handler.sendMessage(Message.obtain(handler, MSG_DADOS, pacotesLidos, 0));
			}
		}

		handler.sendEmptyMessage(MSG_TERMINADO);
	}
}
