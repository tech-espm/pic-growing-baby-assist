package br.espm.capturaaudio;

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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.widget.RemoteViews;

// Referências extras:
// https://developer.android.com/guide/topics/media/mediarecorder
// https://developer.android.com/reference/android/media/MediaRecorder.AudioSource.html
// https://developer.android.com/reference/android/media/AudioFormat.html
// https://developer.android.com/reference/android/media/AudioManager.html
// https://developer.android.com/reference/android/media/AudioRecord.html

public class Servico extends Service implements Runnable, Handler.Callback {

	public interface Callback {
		void onEstadoAlterado(int estado);
		void onErroGravacao();
		// @@@ MSG_DADOS não será necessária no programa de verdade!
		void onDados(int pacotesLidos);
	}

	private static final String CHANNEL_GROUP_ID = "capturaaudiog";
	private static final String CHANNEL_ID = "capturaaudio";

	private static Servico servico;
	private static Callback callback;

	public static final int ESTADO_NOVO = 0;
	public static final int ESTADO_INICIANDO = 1;
	public static final int ESTADO_INICIADO = 2;
	public static final int ESTADO_PARANDO = 3;
	private static volatile int estado = ESTADO_NOVO;

	private static boolean channelCriado = false;

	private static final int MSG_ERRO = 1;
	// @@@ MSG_DADOS não será necessária no programa de verdade!
	private static final int MSG_DADOS = 2;

	private static final int SAMPLE_RATE = 44100;
	private Thread thread;
	private short[] buffer;
	private AudioRecord recorder;
	private PowerManager.WakeLock wakeLock;
	private Handler handler;

	public static void iniciarServico(Application application) {
		if (estado != ESTADO_NOVO)
			return;

		estado = ESTADO_INICIANDO;

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

	private static void notificarErroGravacao() {
		if (callback != null)
			callback.onErroGravacao();
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

		int origem = MediaRecorder.AudioSource.VOICE_RECOGNITION;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			AudioManager audioManager = (AudioManager)application.getSystemService(AUDIO_SERVICE);
			if (audioManager != null && "true".equals(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)))
				origem = MediaRecorder.AudioSource.UNPROCESSED;
		}

		int tamanhoBufferEmBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		// 1 s = 44100 * 2 (16 bits)
		int umSegundoEmBytes = SAMPLE_RATE * 2;
		if (tamanhoBufferEmBytes < umSegundoEmBytes)
			tamanhoBufferEmBytes = umSegundoEmBytes;
		recorder = new AudioRecord(origem, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tamanhoBufferEmBytes);

		// Independente do tamanho do buffer interno do gravador, iremos utilizar um buffer com 1024 amostras
		buffer = new short[1024];

		recorder.startRecording();

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

		if (recorder != null) {
			recorder.stop();
			recorder.release();
			recorder = null;
		}

		buffer = null;

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
			notificarErroGravacao();
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

	@Override
	public void run() {
		// Pede para essa thread ter prioridade sobre as demais
		thread.setPriority(Thread.MAX_PRIORITY);
		android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

		// @@@ MSG_DADOS não será necessária no programa de verdade!
		int pacotesLidos = 0;

		while (estado == ESTADO_INICIADO) {
			int totalLido = 0;
			while (estado == ESTADO_INICIADO && totalLido < buffer.length) {
				int amostrasLidas = recorder.read(buffer, totalLido, buffer.length - totalLido);
				if (amostrasLidas < 0) {
					// Algo saiu errado!
					handler.sendEmptyMessage(MSG_ERRO);
					return;
				}
				totalLido += amostrasLidas;
			}
			if (estado == ESTADO_INICIADO) {
				// Processa as amostras em buffer...
				// Se for fazer o código em C++, em vez de short[], buffer deveria ser um ByteBuffer!!!

				// @@@ MSG_DADOS não será necessária no programa de verdade!
				pacotesLidos++;
				handler.sendMessage(Message.obtain(handler, MSG_DADOS, pacotesLidos, 0));
			}
		}
	}
}
