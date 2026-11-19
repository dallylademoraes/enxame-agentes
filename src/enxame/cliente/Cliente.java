package enxame.cliente;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;
import enxame.comum.Mensagem;

// Pergunta em loop: lê do teclado, envia ao Coordenador e mostra a resposta e o tempo. "sair" encerra.
public class Cliente {

    public static void main(String[] args) throws IOException {
        Scanner teclado = new Scanner(System.in);
        int id = 1; // numero da pergunta, sobe a cada envio

        while (true) {
            System.out.print("Pergunta: ");
            String texto = teclado.nextLine();
            if (texto.equals("sair")) break; // encerra o cliente

            long inicio = System.nanoTime();
            try (Socket s = new Socket(args[0], Integer.parseInt(args[1])); // uma conexao nova por pergunta
                 PrintWriter saida = new PrintWriter(s.getOutputStream(), true);
                 BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

                saida.println(new Mensagem("PERGUNTA", String.valueOf(id++), texto).paraLinha());
                Mensagem r = Mensagem.deLinha(entrada.readLine());
                System.out.println("[" + r.tipo + " " + r.id + "] " + r.conteudo + " (" + (System.nanoTime() - inicio) / 1_000_000 + " ms)");
            } catch (IOException e) {
                System.out.println("Coordenador fora do ar: " + e.getMessage()); // nao fecha o cliente, deixa tentar de novo
            }
        }
    }
}
