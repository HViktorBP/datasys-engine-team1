grammar Sql;

options { caseInsensitive = true; }

script      : (statement ';')+ EOF ;
statement   : createTable | copy | select ;

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

select      : SELECT '*' FROM IDENTIFIER (WHERE predicate)? ;
predicate   : IDENTIFIER comparison=('=' | '<' | '>') literal ;
literal     : STRING_LITERAL | LONG_LITERAL | DOUBLE_LITERAL ;

CREATE : 'CREATE' ;
TABLE  : 'TABLE' ;
COPY   : 'COPY' ;
FROM   : 'FROM' ;
SELECT : 'SELECT' ;
WHERE  : 'WHERE' ;
STRING : 'STRING' ;
LONG   : 'LONG' ;
DOUBLE : 'DOUBLE' ;

IDENTIFIER     : [A-Z_] [A-Z_0-9]* ;
LONG_LITERAL   : '-'? [0-9]+ ;
DOUBLE_LITERAL : '-'? [0-9]+ '.' [0-9]+ ;
STRING_LITERAL : '\'' ~['\r\n\u0080-\u{10FFFF}]* '\'' ;
LINE_COMMENT   : '--' ~[\r\n]* -> skip ;
WS             : [ \t\r\n]+ -> skip ;
