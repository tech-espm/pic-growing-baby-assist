package br.espm.processaaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements Servico.Callback {

	private static final int REQUEST_CODE_AUDIO = 123;

	// @@@ MSG_DADOS não será necessária no programa de verdade!
	private TextView txtPacotesLidos;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		txtPacotesLidos = findViewById(R.id.txtPacotesLidos);

		atualizarTextoBotao(Servico.getEstado());
	}

	@Override
	protected void onStart() {
		super.onStart();

		Servico.setCallback(this);
	}

	@Override
	protected void onStop() {
		super.onStop();

		Servico.setCallback(null);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);

		if (requestCode == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
			escolherAudio();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		if (requestCode == REQUEST_CODE_AUDIO && resultCode == RESULT_OK && data != null)
			Servico.iniciarServico(getApplication(), data.getData());
	}

	private void escolherAudio() {
		Intent intent = new Intent();
		intent.setType("audio/*");
		intent.setAction(Intent.ACTION_GET_CONTENT);
		startActivityForResult(intent, REQUEST_CODE_AUDIO);
	}

	private boolean verificarPermissao() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
				return false;
			}
		}
		return true;
	}

	private void atualizarTextoBotao(int estado) {
		Button btnIniciarParar = findViewById(R.id.btnIniciarParar);

		// @@@ MSG_DADOS não será necessária no programa de verdade!
		txtPacotesLidos.setText("");

		switch (estado) {
		case Servico.ESTADO_NOVO:
			btnIniciarParar.setText(R.string.iniciar);
			break;
		case Servico.ESTADO_INICIADO:
			btnIniciarParar.setText(R.string.parar);
			break;
		default:
			btnIniciarParar.setText(R.string.aguarde);
			break;
		}
	}

	public void iniciarParar(View view) {
		switch (Servico.getEstado()) {
		case Servico.ESTADO_NOVO:
			if (verificarPermissao())
				escolherAudio();
			break;
		case Servico.ESTADO_INICIADO:
			Servico.pararServico();
			break;
		}
	}

	@Override
	public void onEstadoAlterado(int estado) {
		atualizarTextoBotao(estado);
	}

	@Override
	public void onErroDecodificacao() {
		Toast.makeText(this, R.string.erro_decodificacao, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onDados(int pacotesLidos) {
		// @@@ MSG_DADOS não será necessária no programa de verdade!
		txtPacotesLidos.setText(getString(R.string.pacotes_lidos, pacotesLidos));
	}
}
