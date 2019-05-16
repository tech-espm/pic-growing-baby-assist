package br.espm.capturaaudio;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements Servico.Callback {

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
			Servico.iniciarServico(getApplication());
	}

	private boolean verificarPermissao() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
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
				Servico.iniciarServico(getApplication());
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
	public void onErroGravacao() {
		Toast.makeText(this, R.string.erro_gravacao, Toast.LENGTH_SHORT).show();
	}

	@Override
	public void onDados(int pacotesLidos) {
		// @@@ MSG_DADOS não será necessária no programa de verdade!
		txtPacotesLidos.setText(getString(R.string.pacotes_lidos, pacotesLidos));
	}
}
