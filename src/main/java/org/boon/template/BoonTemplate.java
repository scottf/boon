package org.boon.template;

import org.boon.Boon;
import org.boon.Lists;
import org.boon.Str;
import org.boon.StringScanner;
import org.boon.collections.LazyMap;
import org.boon.core.Conversions;
import org.boon.core.reflection.BeanUtils;
import org.boon.expression.ExpressionContext;
import org.boon.primitive.CharBuf;
import org.boon.primitive.CharScanner;
import org.boon.template.support.LoopTagStatus;
import org.boon.template.support.Token;
import org.boon.template.support.TokenTypes;

import java.util.*;

import static org.boon.Boon.puts;
import static org.boon.Exceptions.die;

/**
 * Created by Richard on 9/15/14.
 */
public class BoonTemplate implements Template {


    private CharBuf _buf = CharBuf.create(16);
    private BoonTemplate parentTemplate;
    private TemplateParser parser;
    private String template;
    private ExpressionContext _context;
    private BoonCommandArgumentParser commandArgumentParser = new BoonCommandArgumentParser();

    private void output(Object object) {
        _buf.add(object);
    }

    private void output(Token token) {
        _buf.add(template, token.start(), token.stop());
    }


    public BoonTemplate() {

        parser = new BoonCoreTemplateParser();

    }


    public BoonTemplate(TemplateParser templateParser) {

        parser = templateParser;

    }

//    @Override
//    public String replace(TemplateParser parser, String template, Object... context) {
//
//    }

    @Override
    public String replace(String template, Object... context) {

        initContext(context);
        this.template = template;
        parser.parse(template);
        _buf.readForRecycle();

        Iterator<Token> tokens = parser.getTokenList().iterator();

        while (tokens.hasNext()) {
            final Token token = tokens.next();
            processToken(tokens, token);
        }

        return _buf.toString();
    }

    private String textFromToken(Token token) {
        return template.substring(token.start(), token.stop());
    }


    protected void initContext(final Object... root) {


        this._context = new ExpressionContext(root);


    }


    private Token handleCommand(String template, Token commandToken, Iterator<Token> tokens) {

        this.template = template;

        final String commandText = textFromToken(commandToken);

        int index = StringScanner.findWhiteSpace(commandText);

        String command = Str.endSliceOf(commandText, index);

        String args = Str.sliceOf(commandText, index);


        Map<String, Object> params = commandArgumentParser.parseArguments(args);


        Token bodyToken = tokens.next();


        int bodyTokenStop;
        if (bodyToken.type() == TokenTypes.COMMAND_BODY) {
            bodyTokenStop = bodyToken.stop();
        } else {
            bodyTokenStop = -1;

        }


        List<Token> commandTokens = new ArrayList<>();


        if (bodyToken.start()!=bodyToken.stop()) {
            while (tokens.hasNext()) {
                final Token next = tokens.next();
                commandTokens.add(next);
                if (next.stop() == bodyTokenStop) {
                    break;
                }

            }
        }

        dispatchCommand(command, params, commandTokens);

        return null;

    }


    private void dispatchCommand(
            String command,
            Map<String, Object> params,
            List<Token> commandTokens
    ) {
        switch (command) {
            case "if":
                handleIf(params, commandTokens, true);
                break;

            case "unless":
                handleIf(params, commandTokens, false);
                break;

            case "set":
            case "let":
            case "var":
            case "define":
            case "def":
            case "assign":
                handleSet(params, commandTokens);
                break;

            case "list":
            case "for":
            case "forEach":
            case "foreach":
            case "loop":
            case "each":
                handleLoop(params, commandTokens);

        }

    }


    private void processCommandBodyTokens(List<Token> commandTokens) {
        final Iterator<Token> commandTokenIterators
                = commandTokens.iterator();
        while (commandTokenIterators.hasNext()) {

            Token token = commandTokenIterators.next();
            processToken(commandTokenIterators, token);

        }
    }

    private void processToken(Iterator<Token> tokens, Token token) {
        switch (token.type()) {
            case TEXT:
                output(token);
                break;
            case EXPRESSION:
                String path = textFromToken(token);
                output(_context.lookup(path));
                break;
            case COMMAND:
                handleCommand(template, token, tokens);

                break;


        }
    }


    private String getStringParam(String param, Map<String, Object> params, String defaultValue) {

        String value = Str.toString(params.get(param));
        if (Str.isEmpty(value)) {
            return defaultValue;
        } else if (value.startsWith("$")) {
            return Conversions.toString(_context.lookupWithDefault(value, defaultValue));

        }
        return value;
    }

    private int getIntParam(String param, Map<String, Object> params, int defaultValue) {

        Object value = params.get(param);
        if (value == null) {
            return defaultValue;
        } else if (value instanceof CharSequence && value.toString().startsWith("$")) {
            return Conversions.toInt(_context.lookupWithDefault(value.toString(), defaultValue));

        }
        return Conversions.toInt(value);
    }

    public void displayTokens() {

        for (Token token : parser.getTokenList()) {
            puts("token", token, textFromToken(token));
        }
    }


    private void handleLoop(Map<String, Object> params, List<Token> commandTokens
    ) {


        String itemsName = Str.toString(params.get("items"));
        final Object object = _context.lookup(itemsName);



        List<Object> items;

        if (Boon.isEmpty(object)) {
            items = (List<Object>) params.get("varargs");
        }else {
            items = Conversions.toList(object);
        }



        String var = getStringParam("var", params, "item");
        String varStatus = getStringParam("varStatus", params, "status");


        int begin = getIntParam("begin", params, -1);
        int end = getIntParam("end", params, -1);
        int step = getIntParam("step", params, -1);

        LoopTagStatus status = new LoopTagStatus();

        status.setCount(items.size());

        if (end != -1) {
            status.setEnd(end);
        } else {
            end = items.size();
        }


        if (begin != -1) {
            status.setBegin(begin);
        } else {
            begin = 0;
        }


        if (step != -1) {
            status.setStep(step);
        } else {
            step = 1;
        }


        Map<String, Object> values = new LazyMap();

        int index = 0;

        _context.pushContext(values);

        for (Object item : items) {

            if (index >= begin && index < end && index % step == 0) {

                values.put(var, item);
                values.put(varStatus, status);

                values.put("index", index);

                status.setIndex(index);

                processCommandBodyTokens(commandTokens);
            }
            index++;
        }

        _context.removeLastContext();


    }


    private void handleSet(Map<String, Object> params, List<Token> commandTokens) {


        String var = getStringParam("var", params, "");
        String propertyPath = getStringParam("property", params, "");
        String valueExpression = Str.toString(params.get("value"));
        String targetExpression = Str.toString(params.get("target"));


        Object value = _context.lookup(valueExpression);

        Object bean = _context.lookup(targetExpression);

        if (!Str.isEmpty(propertyPath) && bean!=null ) {
            BeanUtils.idx(bean, propertyPath, value);
        }

        if (!Str.isEmpty(var)) {
            _context.put(var, value);
        }

    }


    private void handleIf(Map<String, Object> params, List<Token> commandTokens,
                          boolean normal) {

        boolean display;

        String var = getStringParam("var", params, "__none");

        final String test = Str.toString(params.get("test"));
        if (test.startsWith("$")) {
            Object o = _context.lookup(test);

            display = Conversions.toBoolean(o);
        } else {
            display = Conversions.toBoolean(test);
        }

        display = display && normal;

        if (!var.equals("__none")) {

            _context.put(var, display);

        }




        if (display) {

            processCommandBodyTokens(commandTokens);

        }



    }



}