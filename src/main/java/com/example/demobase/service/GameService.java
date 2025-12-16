package com.example.demobase.service;

import com.example.demobase.dto.GameDTO;
import com.example.demobase.dto.GameResponseDTO;
import com.example.demobase.model.Game;
import com.example.demobase.model.GameInProgress;
import com.example.demobase.model.Player;
import com.example.demobase.model.Word;
import com.example.demobase.repository.GameInProgressRepository;
import com.example.demobase.repository.GameRepository;
import com.example.demobase.repository.PlayerRepository;
import com.example.demobase.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {
    
    private final GameRepository gameRepository;
    private final GameInProgressRepository gameInProgressRepository;
    private final PlayerRepository playerRepository;
    private final WordRepository wordRepository;
    
    private static final int MAX_INTENTOS = 7;
    private static final int PUNTOS_PALABRA_COMPLETA = 20;
    private static final int PUNTOS_POR_LETRA = 1;
    
    @Transactional
    public GameResponseDTO startGame(Long playerId) {
        
        // TODO: Implementar el método startGame
        // Validar que el jugador existe
        Player player = playerRepository.findById(playerId)
            .orElseThrow(() -> new RuntimeException("Jugador no encontrado con id: " + playerId));

    // Elegir palabra (el test la mockea)
    Word selectedWord = wordRepository.findRandomWord()
            .orElseThrow(() -> new RuntimeException("No hay palabras disponibles para iniciar una partida"));

    // Buscar si ya existe partida en curso para ese jugador y esa palabra (el test mockea esto)
    Optional<GameInProgress> existingGameOpt =
            gameInProgressRepository.findByJugadorAndPalabra(playerId, selectedWord.getId());

    if (existingGameOpt.isPresent()) {
        return buildResponseFromGameInProgress(existingGameOpt.get()); // <- devuelve 5
    }

    // Si no existe, recién ahí marcás como usada y creás nueva partida
    selectedWord.setUtilizada(true);
    wordRepository.save(selectedWord);

    GameInProgress newGame = new GameInProgress();
    newGame.setJugador(player);
    newGame.setPalabra(selectedWord);
    newGame.setIntentosRestantes(MAX_INTENTOS);
    newGame.setLetrasIntentadas("");
    newGame.setFechaInicio(LocalDateTime.now());

    gameInProgressRepository.save(newGame);

    return buildResponseFromGameInProgress(newGame);
    }
    
    @Transactional
    public GameResponseDTO makeGuess(Long playerId, Character letra) {
        
        // TODO: Implementar el método makeGuess
        // Validar que el jugador existe
        if (letra == null) {
            throw new RuntimeException("Debes ingresar una letra válida.");
        }
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new RuntimeException("Jugador no encontrado con id: " + playerId));

        // Convertir la letra a mayúscula
        letra = Character.toUpperCase(letra);

        // Buscar la partida en curso más reciente del jugador
        var partidasEnCurso = gameInProgressRepository.findByJugadorIdOrderByFechaInicioDesc(playerId);

        if (partidasEnCurso.isEmpty()) {
            throw new RuntimeException("El jugador no tiene una partida en curso.");
        }

        // Tomar la partida más reciente
        GameInProgress game = partidasEnCurso.get(0);

        if (game.getIntentosRestantes() <= 0) {
            throw new RuntimeException("La partida ya no tiene intentos restantes.");
        }

        // Obtener letras ya intentadas
        String palabra = game.getPalabra().getPalabra().toUpperCase();

        // Verificar si la letra ya fue intentada
        Set<Character> letrasIntentadas = stringToCharSet(game.getLetrasIntentadas());

        if (letrasIntentadas.contains(letra)) {
            return buildResponseFromGameInProgress(game);
        }

        // Agregar la nueva letra
        letrasIntentadas.add(letra);
        // Verificar si la letra está en la palabra
        game.setLetrasIntentadas(charSetToString(letrasIntentadas));

        // Decrementar intentos solo si la letra es incorrecta
        if (!palabra.contains(letra.toString())) {
            game.setIntentosRestantes(game.getIntentosRestantes() - 1);
        }
        // Generar palabra oculta
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);
        boolean noQuedanIntentos = game.getIntentosRestantes() <= 0;

        if (palabraCompleta || noQuedanIntentos) {
            GameResponseDTO finalResponse = buildResponseFromGameInProgress(game);
            
            int puntaje = finalResponse.getPuntajeAcumulado();
            finalResponse.setPuntajeAcumulado(puntaje);
            saveGame(player, game.getPalabra(), palabraCompleta, puntaje);
            // Si el juego terminó, guardar en Game y eliminar de GameInProgress
            gameInProgressRepository.delete(game);
            return finalResponse;
        }
        // Guardar el estado actualizado
        gameInProgressRepository.save(game);
        // Construir respuesta

        return buildResponseFromGameInProgress(game);
    }
    
    private GameResponseDTO buildResponseFromGameInProgress(GameInProgress gameInProgress) {
        String palabra = gameInProgress.getPalabra().getPalabra().toUpperCase();
        Set<Character> letrasIntentadas = stringToCharSet(gameInProgress.getLetrasIntentadas());
        String palabraOculta = generateHiddenWord(palabra, letrasIntentadas);
        boolean palabraCompleta = palabraOculta.equals(palabra);
        
        GameResponseDTO response = new GameResponseDTO();
        response.setPalabraOculta(palabraOculta);
        response.setLetrasIntentadas(new ArrayList<>(letrasIntentadas));
        response.setIntentosRestantes(gameInProgress.getIntentosRestantes());
        response.setPalabraCompleta(palabraCompleta);
        
        int puntaje = calculateScore(palabra, letrasIntentadas, palabraCompleta, gameInProgress.getIntentosRestantes());
        response.setPuntajeAcumulado(puntaje);
        
        return response;
    }
    
    private Set<Character> stringToCharSet(String str) {
        Set<Character> set = new HashSet<>();
        if (str != null && !str.isEmpty()) {
            String[] chars = str.split(",");
            for (String c : chars) {
                if (!c.trim().isEmpty()) {
                    set.add(c.trim().charAt(0));
                }
            }
        }
        return set;
    }
    
    private String charSetToString(Set<Character> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        return set.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
    
    private int calculateScore(String palabra, Set<Character> letrasIntentadas, boolean palabraCompleta, int intentosRestantes) {
        if (palabraCompleta) {
            return PUNTOS_PALABRA_COMPLETA;
        } else if (intentosRestantes == 0) {
            // Contar letras correctas encontradas
            long letrasCorrectas = letrasIntentadas.stream()
                    .filter(letra -> palabra.indexOf(letra) >= 0)
                    .count();
            return (int) (letrasCorrectas * PUNTOS_POR_LETRA);
        }
        return 0;
    }
    
    private String generateHiddenWord(String palabra, Set<Character> letrasIntentadas) {
        StringBuilder hidden = new StringBuilder();
        for (char c : palabra.toCharArray()) {
            if (letrasIntentadas.contains(c) || c == ' ') {
                hidden.append(c);
            } else {
                hidden.append('_');
            }
        }
        return hidden.toString();
    }
    
    @Transactional
    private void saveGame(Player player, Word word, boolean ganado, int puntaje) {
        // Asegurar que la palabra esté marcada como utilizada
        if (!word.getUtilizada()) {
            word.setUtilizada(true);
            wordRepository.save(word);
        }
        
        Game game = new Game();
        game.setJugador(player);
        game.setPalabra(word);
        game.setResultado(ganado ? "GANADO" : "PERDIDO");
        game.setPuntaje(puntaje);
        game.setFechaPartida(LocalDateTime.now());
        gameRepository.save(game);
    }
    
    public List<GameDTO> getGamesByPlayer(Long playerId) {
        return gameRepository.findByJugadorId(playerId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    public List<GameDTO> getAllGames() {
        return gameRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }
    
    private GameDTO toDTO(Game game) {
        GameDTO dto = new GameDTO();
        dto.setId(game.getId());
        dto.setIdJugador(game.getJugador().getId());
        dto.setNombreJugador(game.getJugador().getNombre());
        dto.setResultado(game.getResultado());
        dto.setPuntaje(game.getPuntaje());
        dto.setFechaPartida(game.getFechaPartida());
        dto.setPalabra(game.getPalabra() != null ? game.getPalabra().getPalabra() : null);
        return dto;
    }
}

