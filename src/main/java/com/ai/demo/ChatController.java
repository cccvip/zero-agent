//package com.ai.demo;
//
//import com.ai.demo.react.ReActAgent;
//import jakarta.annotation.Resource;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.ArrayList;
//
//@RestController
//public class ChatController {
//
////    private final ChatClient chatClient;
////
////    private final TimeTool tool;
////
////    public ChatController(ChatClient.Builder builder, TimeTool tool) {
////        this.chatClient = builder.build();
////        this.tool = tool;
////    }
//
//    @Resource
//    ReActAgent reActAgent;
//
//    @GetMapping("/chat")
//    public String chat(@RequestParam String q) {
//        return reActAgent.run(new ArrayList<>(),q);
////         ChatClient.CallResponseSpec chatResponse =
////                 chatClient.prompt()
////                         .user(q)
////                         .call();;
////
////         ChatClientResponse chatClientResponse = chatResponse.chatClientResponse();
////         ChatResponse response = chatClientResponse.chatResponse();
////
////         ChatResponseMetadata chatResponseMetadata = response.getMetadata();
////         System.out.println(JSON.toJSONString(chatResponseMetadata));
////
////         List<Generation> generations = response.getResults();
////         for(Generation g:generations){
////             System.out.println(JSON.toJSONString(g));
////         }
//    }
//}
