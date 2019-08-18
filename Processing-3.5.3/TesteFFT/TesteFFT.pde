import processing.sound.*;

// Vamos analizar 2048 amostras por vez.
static final int TAMANHO_FFT = 2048;
// Das 2048 amostras serão gerados 1024 bins com informação válida (de 0Hz até 22050Hz, por exemplo).
static final int TAMANHO_FFT_VALIDO = TAMANHO_FFT / 2;
// Dos 1024 bins, iremos analizar apenas a primeira metade (de 0Hz até 11025Hz, por exemplo).
static final int TAMANHO_FFT_ANALIZADO = TAMANHO_FFT_VALIDO / 2;

SoundFile arquivo;
Waveform waveform;

public void setup() {
  // Configura o tamanho da tela.
  size(512, 256);
  
  // Configura o Processing para chamar a função draw() 30 vezes por segundo.
  frameRate(30);

  // Cria o arquivo de música e pede para ele tocar para sempre em loop.
  arquivo = new SoundFile(this, "D:\\Temp\\Sunset Moments - A Winter's Tale mono.wav");
  arquivo.loop();

  // Cria um objeto que irá obter as amostras do áudio.
  waveform = new Waveform(this, TAMANHO_FFT);
  waveform.input(arquivo);
  
  // Executa a FFT uma primeira vez, sem dados, para preencher as tabelas auxiliares.
  for (int i = 0; i < TAMANHO_FFT; i++) {
    waveform.data[i] = 0;
  }
  prepararFFT(TAMANHO_FFT, waveform.data);
}

public void draw() {
  // Limpa a tela e configura as cores a serem utilizadas para desenhar.
  background(0);
  stroke(255);
  strokeWeight(1);
  noFill();

  // Pede para que waveform obtenha 2048 amostras do áudio no domínio do tempo.
  waveform.analyze();

  float[] dados = waveform.data;

  // waveform.data contém as 2048 amostras que desejamos. Porém, os valores vão de -1 até 1.
  // Quando for em Java para Android, os valores serão de -32768 até 32767. Por isso, vamos
  // multiplicar todas as amostras por 32767.
  for (int i = 0; i < TAMANHO_FFT; i++) {
    dados[i] *= 32767;
  }
  
  // Na entrada dados deve conter as amostras do áudio no tempo. Na saída, a função fft()
  // devolverá os bins na frequência, no seguinte formato/disposição (o tamanho do vetor é n):
  //
  // índice no vetor  0   1    2  3  4  5  ..... n-2        n-1
  // conteúdo         Rdc Rnyq R1 I1 R2 I2       R(n-1)/2   I(n-1)/2
  fft(dados);
  
  // Como fft() devolve os bins na forma de números complexos na forma retangular, precisamos
  // calcular a magnitude de cada um dos bins que iremos analizar.
  for (int i = 0; i < TAMANHO_FFT_ANALIZADO; i++) {
    // O algoritmo que estamos usando para calcular a FFT multiplica toda a saída por
    // TAMANHO_FFT / 2, logo, precisamos multiplicar pelo oposto para desfazer isso.
    float real = dados[i * 2] * (2.0f / TAMANHO_FFT);
    float imag = dados[(i * 2) + 1] * (2.0f / TAMANHO_FFT);
    dados[i] = (float)Math.sqrt((real * real) + (imag * imag));
  }
  
  // Desenha uma linha branca na tela para mostrar as amplitudes.
  beginShape();
  for (int i = 0; i < TAMANHO_FFT_ANALIZADO; i++) {
    vertex(
      map(i, 0, TAMANHO_FFT_ANALIZADO, 0, width),
      map(dados[i], 0, 32767, height, 0)
    );
  }
  endShape();
}
